/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.util.{Clock, SystemClock, Utils}

/**
 * BlacklistTracker is designed to track problematic executors and nodes.  It supports blacklisting
 * executors and nodes across an entire application (with a periodic expiry).  TaskSetManagers add
 * additional blacklisting of executors and nodes for individual tasks and stages which works in
 * concert with the blacklisting here.
 *
 * The tracker needs to deal with a variety of workloads, eg.:
 *
 *  * bad user code --  this may lead to many task failures, but that should not count against
 *      individual executors
 *  * many small stages -- this may prevent a bad executor for having many failures within one
 *      stage, but still many failures over the entire application
 *  * "flaky" executors -- they don't fail every task, but are still faulty enough to merit
 *      blacklisting
 *
 * See the design doc on SPARK-8425 for a more in-depth discussion.
 *
 * THREADING: As with most helpers of TaskSchedulerImpl, this is not thread-safe.  Though it is
 * called by multiple threads, callers must already have a lock on the TaskSchedulerImpl.  The
 * one exception is [[nodeBlacklist()]], which can be called without holding a lock.
 */
private[scheduler] class BlacklistTracker (
    conf: SparkConf,
    clock: Clock = new SystemClock()) extends Logging {

  private val MAX_FAILURES_PER_EXEC = conf.getInt(BlacklistConfs.MAX_FAILURES_PER_EXEC, 2)
  private val MAX_FAILED_EXEC_PER_NODE = conf.getInt(BlacklistConfs.MAX_FAILED_EXEC_PER_NODE, 2)
  val BLACKLIST_TIMEOUT_MILLIS = BlacklistTracker.getBlacklistTimeout(conf)

  /**
   * A map from executorId to information on task failures.  Tracks the time of each task failure,
   * so that we can avoid blacklisting executors due to failures that are very far apart.
   */
  private val executorIdToFailureList: HashMap[String, ExecutorFailureList] = new HashMap()
  val executorIdToBlacklistStatus: HashMap[String, BlacklistedExecutor] = new HashMap()
  val nodeIdToBlacklistExpiryTime: HashMap[String, Long] = new HashMap()
  /**
   * An immutable copy of the set of nodes that are currently blacklisted.  Kept in an
   * AtomicReference to make [[nodeBlacklist()]] thread-safe.
   */
  private val _nodeBlacklist: AtomicReference[Set[String]] = new AtomicReference(Set())
  /**
   * Time when the next blacklist will expire. Used as a shortcut to avoid iterating over all
   * entries in the blacklist when none will have expired.
   */
  private var nextExpiryTime: Long = Long.MaxValue
  /**
   * Mapping from nodes to all of the executors that have been blacklisted on that node. We do *not*
   * remove from this when executors are removed from spark, so we can track when we get multiple
   * successive blacklisted executors on one node.  Nonetheless, it will not grow too large because
   * there cannot be many blacklisted executors on one node, before we stop requesting more
   * executors on that node, and we periodically clean up the list of blacklisted executors.
   */
  val nodeToFailedExecs: HashMap[String, HashSet[String]] = new HashMap()

  def applyBlacklistTimeout(): Unit = {
    val now = clock.getTimeMillis()
    // quickly check if we've got anything to expire from blacklist -- if not, avoid doing any work
    if (now > nextExpiryTime) {
      // Apply the timeout to individual tasks.  This is to prevent one-off failures that are very
      // spread out in time (and likely have nothing to do with problems on the executor) from
      // triggering blacklisting.  However, note that we do *not* remove executors and nodes from
      // the blacklist as we expire individual task failures -- each have their own timeout.  Eg.,
      // suppose:
      // * timeout = 10, maxFailuresPerExec = 2
      // * Task 1 fails on exec 1 at time 0
      // * Task 2 fails on exec 1 at time 5
      // -->  exec 1 is blacklisted from time 5 - 15.
      // This is to simplify the implementation, as well as keep the behavior easier to understand
      // for the end user.
      executorIdToFailureList.values.foreach { executorFailureList =>
        executorFailureList.dropFailuresWithTimeoutBefore(now)
      }

      // Apply the timeout to blacklisted nodes and executors
      val execsToUnblacklist = executorIdToBlacklistStatus.filter(_._2.expiryTime < now).keys
      if (execsToUnblacklist.nonEmpty) {
        // Un-blacklist any executors that have been blacklisted longer than the blacklist timeout.
        logInfo(s"Removing executors $execsToUnblacklist from blacklist because the blacklist " +
          s"has timed out")
        execsToUnblacklist.foreach { exec =>
          val status = executorIdToBlacklistStatus.remove(exec).get
          val failedExecsOnNode = nodeToFailedExecs(status.node)
          failedExecsOnNode.remove(exec)
          if (failedExecsOnNode.isEmpty) {
            nodeToFailedExecs.remove(status.node)
          }
        }
      }
      if (executorIdToBlacklistStatus.nonEmpty) {
        nextExpiryTime = executorIdToBlacklistStatus.map{_._2.expiryTime}.min
      } else {
        nextExpiryTime = Long.MaxValue
      }
      val nodesToUnblacklist = nodeIdToBlacklistExpiryTime.filter(_._2 < now).keys
      if (nodesToUnblacklist.nonEmpty) {
        // Un-blacklist any nodes that have been blacklisted longer than the blacklist timeout.
        logInfo(s"Removing nodes $nodesToUnblacklist from blacklist because the blacklist " +
          s"has timed out")
        nodesToUnblacklist.foreach { node => nodeIdToBlacklistExpiryTime.remove(node) }
        _nodeBlacklist.set(nodeIdToBlacklistExpiryTime.keySet.toSet)
      }
    }
 }

  def updateBlacklistForSuccessfulTaskSet(
      stageId: Int,
      stageAttemptId: Int,
      failuresByExec: HashMap[String, ExecutorFailuresInTaskSet]): Unit = {
    // if any tasks failed, we count them towards the overall failure count for the executor at
    // this point.
    failuresByExec.foreach { case (exec, failuresInTaskSet) =>
      val allExecutorFailures =
        executorIdToFailureList.getOrElseUpdate(exec, new ExecutorFailureList)
      allExecutorFailures.addFailures(stageId, stageAttemptId, failuresInTaskSet)
      val newTotal = allExecutorFailures.numUniqueTaskFailures
      if (allExecutorFailures.minExpiryTime < nextExpiryTime) {
        nextExpiryTime = allExecutorFailures.minExpiryTime
      }

      if (newTotal >= MAX_FAILURES_PER_EXEC) {
        logInfo(s"Blacklisting executor id: $exec because it has $newTotal" +
          s" task failures in successful task sets")
        val now = clock.getTimeMillis()
        val expiryTime = now + BLACKLIST_TIMEOUT_MILLIS
        val node = failuresInTaskSet.node
        executorIdToBlacklistStatus.put(exec, BlacklistedExecutor(node, expiryTime))
        executorIdToFailureList.remove(exec)
        if (expiryTime < nextExpiryTime) {
          nextExpiryTime = expiryTime
        }

        // In addition to blacklisting the executor, we also update the data for failures on the
        // node, and potentially put the entire node into a blacklist as well.
        val blacklistedExecsOnNode = nodeToFailedExecs.getOrElseUpdate(node, HashSet[String]())
        blacklistedExecsOnNode += exec
        if (blacklistedExecsOnNode.size >= MAX_FAILED_EXEC_PER_NODE) {
          logInfo(s"Blacklisting node $node because it has ${blacklistedExecsOnNode.size} " +
            s"executors blacklisted: ${blacklistedExecsOnNode}")
          nodeIdToBlacklistExpiryTime.put(node, expiryTime)
          _nodeBlacklist.set(nodeIdToBlacklistExpiryTime.keySet.toSet)
        }
      }
    }
  }

  def isExecutorBlacklisted(executorId: String): Boolean = {
    executorIdToBlacklistStatus.contains(executorId)
  }

  /**
   * Get the full set of nodes that are blacklisted.  Unlike other methods in this class, this *IS*
   * thread-safe -- no lock required on a taskScheduler.
   */
  def nodeBlacklist(): Set[String] = {
    _nodeBlacklist.get()
  }

  def isNodeBlacklisted(node: String): Boolean = {
    nodeIdToBlacklistExpiryTime.contains(node)
  }

  def handleRemovedExecutor(executorId: String): Unit = {
    // We intentionally do not clean up executors that are already blacklisted in nodeToFailedExecs,
    // so that if another executor on the same node gets blacklisted, we can blacklist the entire
    // node.  We also can't clean up executorIdToBlacklistStatus, so we can eventually remove
    // the executor after the timeout.  Despite not clearing those structures here, we don't expect
    // they will grow too big since you won't get too many executors on one node, and the timeout
    // will clear it up periodically in any case.
    executorIdToFailureList -= executorId
  }
}


private[scheduler] object BlacklistTracker extends Logging {

  private val DEFAULT_TIMEOUT = "1h"

  /**
   * Returns true if the blacklist is enabled, based on checking the configuration in the following
   * order:
   * 1. Is it specifically enabled or disabled?
   * 2. Is it enabled via the legacy timeout conf?
   * 3. Use the default for the spark-master:
   *   - off for local mode
   *   - on for distributed modes (including local-cluster)
   */
  def isBlacklistEnabled(conf: SparkConf): Boolean = {
    conf.getOption(BlacklistConfs.BLACKLIST_ENABLED) match {
      case Some(isEnabled) =>
        isEnabled.toBoolean
      case None =>
        // if they've got a non-zero setting for the legacy conf, always enable the blacklist,
        // otheriwse, its off by default, just in this cloudera-preview.
        val legacyKey = BlacklistConfs.BLACKLIST_LEGACY_TIMEOUT_CONF
        conf.getOption(legacyKey) match {
          case Some(legacyTimeoutStr) =>
            val legacyTimeout = legacyTimeoutStr.toLong
            if (legacyTimeout == 0) {
              logWarning(s"Turning off blacklisting due to legacy configuaration:" +
                s" $legacyKey == 0")
              false
            } else {
              // mostly this is necessary just for tests, since real users that want the blacklist
              // will get it anyway by default
              logWarning(s"Turning on blacklisting due to legacy configuration:" +
                s" $legacyKey > 0")
              true
            }
          case None =>
            // off by default in this cloudera-preview
            false
        }
    }
  }

  def getBlacklistTimeout(conf: SparkConf): Long = {
    Utils.timeStringAsMs(
      conf.getOption(BlacklistConfs.BLACKLIST_TIMEOUT_CONF).getOrElse {
        conf.getOption(BlacklistConfs.BLACKLIST_LEGACY_TIMEOUT_CONF).getOrElse(DEFAULT_TIMEOUT)
    })
  }
}

/** Failures for one executor, within one taskset */
private[scheduler] final class ExecutorFailuresInTaskSet(val node: String) {
  /**
   * Mapping from index of the tasks in the taskset, to the number of times it has failed on this
   * executor.
   */
  val taskToFailureCountAndExpiryTime = HashMap[Int, (Int, Long)]()
  def updateWithFailure(taskIndex: Int, failureExpiryTime: Long): Unit = {
    val (prevFailureCount, prevFailureExpiryTime) =
      taskToFailureCountAndExpiryTime.getOrElse(taskIndex, (0, -1L))
    assert(failureExpiryTime >= prevFailureExpiryTime)
    taskToFailureCountAndExpiryTime(taskIndex) = (prevFailureCount + 1, failureExpiryTime)
  }
  def numUniqueTasksWithFailures: Int = taskToFailureCountAndExpiryTime.size


  override def toString(): String = {
    s"numUniqueTasksWithFailures= $numUniqueTasksWithFailures; " +
      s"tasksToFailureCount = $taskToFailureCountAndExpiryTime"
  }
}

/**
 * Tracks all failures for one executor (that have not passed the timeout).  Designed to efficiently
 * remove failures that are older than the timeout, and query for the number of unique failed tasks.
 */
private[scheduler] final class ExecutorFailureList extends Logging {

  private case class TaskId(stage: Int, stageAttempt: Int, taskIndex: Int)

  /**
   * All failures on this executor in successful task sets, sorted by time ascending.
   */
  private var failures = ArrayBuffer[(TaskId, Long)]()

  def addFailures(
      stage: Int,
      stageAttempt: Int,
      failuresInTaskSet: ExecutorFailuresInTaskSet): Unit = {
    // The new failures may interleave with the old ones, so rebuild the failures in sorted order.
    // This shouldn't be expensive because if there were a lot of failures, the executor would
    // have been blacklisted.
    if (failuresInTaskSet.taskToFailureCountAndExpiryTime.nonEmpty) {
      failuresInTaskSet.taskToFailureCountAndExpiryTime.foreach { case (taskIdx, (_, time)) =>
        failures += ((TaskId(stage, stageAttempt, taskIdx), time))
      }
      // sort by failure time, so we can quickly determine if any failure has gone past the timeout
      failures = failures.sortBy(_._2)
    }
  }

  def minExpiryTime: Long = failures.head._2

  /**
   * The number of unique tasks that failed on this executor.  Only counts failures within the
   * timeout, and in successful tasksets.
   */
  def numUniqueTaskFailures: Int = failures.size

  def dropFailuresWithTimeoutBefore(dropBefore: Long): Unit = {
    if (minExpiryTime < dropBefore) {
      val minIndexToKeep = failures.indexWhere(_._2 >= dropBefore)
      if (minIndexToKeep == -1) {
        failures.clear()
      } else {
        failures = failures.drop(minIndexToKeep)
      }
    }
  }
}

private final case class BlacklistedExecutor(node: String, expiryTime: Long)

/**
 * Holds all the conf keys related to blacklist.  In SPARK-8425, there is a ConfigBuilder
 * helper, so this code is all custom for the backport.
 */
private[spark] object BlacklistConfs {
  val BLACKLIST_ENABLED = "spark.blacklist.enabled"

  val MAX_TASK_ATTEMPTS_PER_EXECUTOR = "spark.blacklist.task.maxTaskAttemptsPerExecutor"

  val MAX_TASK_ATTEMPTS_PER_NODE = "spark.blacklist.task.maxTaskAttemptsPerNode"

  val MAX_FAILURES_PER_EXEC = "spark.blacklist.application.maxFailedTasksPerExecutor"

  val MAX_FAILURES_PER_EXEC_STAGE = "spark.blacklist.stage.maxFailedTasksPerExecutor"

  val MAX_FAILED_EXEC_PER_NODE = "spark.blacklist.application.maxFailedExecutorsPerNode"

  val MAX_FAILED_EXEC_PER_NODE_STAGE = "spark.blacklist.stage.maxFailedExecutorsPerNode"

  val BLACKLIST_TIMEOUT_CONF = "spark.blacklist.timeout"

  val BLACKLIST_LEGACY_TIMEOUT_CONF = "spark.scheduler.executorTaskBlacklistTime"
}