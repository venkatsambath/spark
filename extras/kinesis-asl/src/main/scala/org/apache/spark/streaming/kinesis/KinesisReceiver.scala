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
package org.apache.spark.streaming.kinesis

import java.util.UUID

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.mutable
import scala.util.control.NonFatal

import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, DefaultAWSCredentialsProviderChain}
import com.amazonaws.services.kinesis.clientlibrary.interfaces.{IRecordProcessor, IRecordProcessorFactory}
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.{InitialPositionInStream, KinesisClientLibConfiguration, Worker}
import com.amazonaws.services.kinesis.model.Record

import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.Duration
import org.apache.spark.streaming.receiver.{BlockGenerator, BlockGeneratorListener, Receiver}
import org.apache.spark.util.Utils
import org.apache.spark.{Logging, SparkEnv}


private[kinesis]
case class SerializableAWSCredentials(accessKeyId: String, secretKey: String)
  extends AWSCredentials {
  override def getAWSAccessKeyId: String = accessKeyId
  override def getAWSSecretKey: String = secretKey
}

/**
 * Custom AWS Kinesis-specific implementation of Spark Streaming's Receiver.
 * This implementation relies on the Kinesis Client Library (KCL) Worker as described here:
 * https://github.com/awslabs/amazon-kinesis-client
 *
 * The way this Receiver works is as follows:
 * - The receiver starts a KCL Worker, which is essentially runs a threadpool of multiple
 *   KinesisRecordProcessor
 * - Each KinesisRecordProcessor receives data from a Kinesis shard in batches. Each batch is
 *   inserted into a Block Generator, and the corresponding range of sequence numbers is recorded.
 * - When the block generator defines a block, then the recorded sequence number ranges that were
 *   inserted into the block are recorded separately for being used later.
 * - When the block is ready to be pushed, the block is pushed and the ranges are reported as
 *   metadata of the block. In addition, the ranges are used to find out the latest sequence
 *   number for each shard that can be checkpointed through the DynamoDB.
 * - Periodically, each KinesisRecordProcessor checkpoints the latest successfully stored sequence
 *   number for it own shard.
 *
 * @param streamName   Kinesis stream name
 * @param endpointUrl  Url of Kinesis service (e.g., https://kinesis.us-east-1.amazonaws.com)
 * @param regionName  Region name used by the Kinesis Client Library for
 *                    DynamoDB (lease coordination and checkpointing) and CloudWatch (metrics)
 * @param initialPositionInStream  In the absence of Kinesis checkpoint info, this is the
 *                                 worker's initial starting position in the stream.
 *                                 The values are either the beginning of the stream
 *                                 per Kinesis' limit of 24 hours
 *                                 (InitialPositionInStream.TRIM_HORIZON) or
 *                                 the tip of the stream (InitialPositionInStream.LATEST).
 * @param checkpointAppName  Kinesis application name. Kinesis Apps are mapped to Kinesis Streams
 *                 by the Kinesis Client Library.  If you change the App name or Stream name,
 *                 the KCL will throw errors.  This usually requires deleting the backing
 *                 DynamoDB table with the same name this Kinesis application.
 * @param checkpointInterval  Checkpoint interval for Kinesis checkpointing.
 *                            See the Kinesis Spark Streaming documentation for more
 *                            details on the different types of checkpoints.
 * @param storageLevel Storage level to use for storing the received objects
 * @param awsCredentialsOption Optional AWS credentials, used when user directly specifies
 *                             the credentials
 */
private[kinesis] class KinesisReceiver(
    val streamName: String,
    endpointUrl: String,
    regionName: String,
    initialPositionInStream: InitialPositionInStream,
    checkpointAppName: String,
    checkpointInterval: Duration,
    storageLevel: StorageLevel,
    awsCredentialsOption: Option[SerializableAWSCredentials]
  ) extends Receiver[Array[Byte]](storageLevel) with Logging { receiver =>

  /*
   * =================================================================================
   * The following vars are initialize in the onStart() method which executes in the
   * Spark worker after this Receiver is serialized and shipped to the worker.
   * =================================================================================
   */

  /**
   * workerId is used by the KCL should be based on the ip address of the actual Spark Worker
   * where this code runs (not the driver's IP address.)
   */
  @volatile private var workerId: String = null

  /**
   * Worker is the core client abstraction from the Kinesis Client Library (KCL).
   * A worker can process more than one shards from the given stream.
   * Each shard is assigned its own IRecordProcessor and the worker run multiple such
   * processors.
   */
  @volatile private var worker: Worker = null
  @volatile private var workerThread: Thread = null

  /** BlockGenerator used to generates blocks out of Kinesis data */
  @volatile private var blockGenerator: BlockGenerator = null

  /**
   * Sequence number ranges added to the current block being generated.
   * Accessing and updating of this map is synchronized by locks in BlockGenerator.
   */
  private val seqNumRangesInCurrentBlock = new mutable.ArrayBuffer[SequenceNumberRange]

  /** Sequence number ranges of data added to each generated block */
  private val blockIdToSeqNumRanges = new mutable.HashMap[StreamBlockId, SequenceNumberRanges]
    with mutable.SynchronizedMap[StreamBlockId, SequenceNumberRanges]

  /**
   * Latest sequence number ranges that have been stored successfully.
   * This is used for checkpointing through KCL */
  private val shardIdToLatestStoredSeqNum = new mutable.HashMap[String, String]
    with mutable.SynchronizedMap[String, String]
  /**
   * This is called when the KinesisReceiver starts and must be non-blocking.
   * The KCL creates and manages the receiving/processing thread pool through Worker.run().
   */
  override def onStart() {
    blockGenerator = supervisor.createBlockGenerator(new GeneratedBlockHandler)

    workerId = Utils.localHostName() + ":" + UUID.randomUUID()

    // KCL config instance
    val awsCredProvider = resolveAWSCredentialsProvider()
    val kinesisClientLibConfiguration =
      new KinesisClientLibConfiguration(checkpointAppName, streamName, awsCredProvider, workerId)
      .withKinesisEndpoint(endpointUrl)
      .withInitialPositionInStream(initialPositionInStream)
      .withTaskBackoffTimeMillis(500)
      .withRegionName(regionName)

   /*
    *  RecordProcessorFactory creates impls of IRecordProcessor.
    *  IRecordProcessor adapts the KCL to our Spark KinesisReceiver via the
    *  IRecordProcessor.processRecords() method.
    *  We're using our custom KinesisRecordProcessor in this case.
    */
    val recordProcessorFactory = new IRecordProcessorFactory {
      override def createProcessor: IRecordProcessor = new KinesisRecordProcessor(receiver,
        workerId, new KinesisCheckpointState(checkpointInterval))
    }

    worker = new Worker(recordProcessorFactory, kinesisClientLibConfiguration)
    workerThread = new Thread() {
      override def run(): Unit = {
        try {
          worker.run()
        } catch {
          case NonFatal(e) =>
            restart("Error running the KCL worker in Receiver", e)
        }
      }
    }

    blockIdToSeqNumRanges.clear()
    blockGenerator.start()

    workerThread.setName(s"Kinesis Receiver ${streamId}")
    workerThread.setDaemon(true)
    workerThread.start()
    logInfo(s"Started receiver with workerId $workerId")
  }

  /**
   * This is called when the KinesisReceiver stops.
   * The KCL worker.shutdown() method stops the receiving/processing threads.
   * The KCL will do its best to drain and checkpoint any in-flight records upon shutdown.
   */
  override def onStop() {
    if (workerThread != null) {
      if (worker != null) {
        worker.shutdown()
        worker = null
      }
      workerThread.join()
      workerThread = null
      logInfo(s"Stopped receiver for workerId $workerId")
    }
    workerId = null
  }

  /** Add records of the given shard to the current block being generated */
  private[kinesis] def addRecords(shardId: String, records: java.util.List[Record]): Unit = {
    if (records.size > 0) {
      val dataIterator = records.iterator().map { record =>
        val byteBuffer = record.getData()
        val byteArray = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(byteArray)
        byteArray
      }
      val metadata = SequenceNumberRange(streamName, shardId,
        records.get(0).getSequenceNumber(), records.get(records.size() - 1).getSequenceNumber())
      blockGenerator.addMultipleDataWithCallback(dataIterator, metadata)

    }
  }

  /** Get the latest sequence number for the given shard that can be checkpointed through KCL */
  private[kinesis] def getLatestSeqNumToCheckpoint(shardId: String): Option[String] = {
    shardIdToLatestStoredSeqNum.get(shardId)
  }

  /**
   * Remember the range of sequence numbers that was added to the currently active block.
   * Internally, this is synchronized with `finalizeRangesForCurrentBlock()`.
   */
  private def rememberAddedRange(range: SequenceNumberRange): Unit = {
    seqNumRangesInCurrentBlock += range
  }

  /**
   * Finalize the ranges added to the block that was active and prepare the ranges buffer
   * for next block. Internally, this is synchronized with `rememberAddedRange()`.
   */
  private def finalizeRangesForCurrentBlock(blockId: StreamBlockId): Unit = {
    blockIdToSeqNumRanges(blockId) = SequenceNumberRanges(seqNumRangesInCurrentBlock.toArray)
    seqNumRangesInCurrentBlock.clear()
    logDebug(s"Generated block $blockId has $blockIdToSeqNumRanges")
  }

  /** Store the block along with its associated ranges */
  private def storeBlockWithRanges(
      blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[Array[Byte]]): Unit = {
    val rangesToReportOption = blockIdToSeqNumRanges.remove(blockId)
    if (rangesToReportOption.isEmpty) {
      stop("Error while storing block into Spark, could not find sequence number ranges " +
        s"for block $blockId")
      return
    }

    val rangesToReport = rangesToReportOption.get
    var attempt = 0
    var stored = false
    var throwable: Throwable = null
    while (!stored && attempt <= 3) {
      try {
        store(arrayBuffer, rangesToReport)
        stored = true
      } catch {
        case NonFatal(th) =>
          attempt += 1
          throwable = th
      }
    }
    if (!stored) {
      stop("Error while storing block into Spark", throwable)
    }

    // Update the latest sequence number that have been successfully stored for each shard
    // Note that we are doing this sequentially because the array of sequence number ranges
    // is assumed to be
    rangesToReport.ranges.foreach { range =>
      shardIdToLatestStoredSeqNum(range.shardId) = range.toSeqNumber
    }
  }

  /**
   * If AWS credential is provided, return a AWSCredentialProvider returning that credential.
   * Otherwise, return the DefaultAWSCredentialsProviderChain.
   */
  private def resolveAWSCredentialsProvider(): AWSCredentialsProvider = {
    awsCredentialsOption match {
      case Some(awsCredentials) =>
        logInfo("Using provided AWS credentials")
        new AWSCredentialsProvider {
          override def getCredentials: AWSCredentials = awsCredentials
          override def refresh(): Unit = { }
        }
      case None =>
        logInfo("Using DefaultAWSCredentialsProviderChain")
        new DefaultAWSCredentialsProviderChain()
    }
  }


  /**
   * Class to handle blocks generated by this receiver's block generator. Specifically, in
   * the context of the Kinesis Receiver, this handler does the following.
   *
   * - When an array of records is added to the current active block in the block generator,
   *   this handler keeps track of the corresponding sequence number range.
   * - When the currently active block is ready to sealed (not more records), this handler
   *   keep track of the list of ranges added into this block in another H
   */
  private class GeneratedBlockHandler extends BlockGeneratorListener {

    /**
     * Callback method called after a data item is added into the BlockGenerator.
     * The data addition, block generation, and calls to onAddData and onGenerateBlock
     * are all synchronized through the same lock.
     */
    def onAddData(data: Any, metadata: Any): Unit = {
      rememberAddedRange(metadata.asInstanceOf[SequenceNumberRange])
    }

    /**
     * Callback method called after a block has been generated.
     * The data addition, block generation, and calls to onAddData and onGenerateBlock
     * are all synchronized through the same lock.
     */
    def onGenerateBlock(blockId: StreamBlockId): Unit = {
      finalizeRangesForCurrentBlock(blockId)
    }

    /** Callback method called when a block is ready to be pushed / stored. */
    def onPushBlock(blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[_]): Unit = {
      storeBlockWithRanges(blockId,
        arrayBuffer.asInstanceOf[mutable.ArrayBuffer[Array[Byte]]])
    }

    /** Callback called in case of any error in internal of the BlockGenerator */
    def onError(message: String, throwable: Throwable): Unit = {
      reportError(message, throwable)
    }
  }
}