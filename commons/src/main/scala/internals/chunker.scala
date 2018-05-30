package com.mfglabs.stream

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString


/**
 * Inspired by https://doc.akka.io/docs/akka/2.5/stream/stream-cookbook.html#working-with-io
 */
class Chunker(val chunkSize: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {
  val in = Inlet[ByteString]("Chunker.in")
  val out = Outlet[ByteString]("Chunker.out")

  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var buffer = ByteString.empty

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        handle()
      }
    })

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        buffer ++= elem

        handle()
      }

      /**
       * We do not complete unless the buffer is empty.
       * We check out availaibility since a Pull might have been lost when closing.
       */
      override def onUpstreamFinish(): Unit = {
        if( buffer.isEmpty ) completeStage()
        else if( isAvailable(out) ) emitChunk()
      }
    })

    /**
     * Work for both case
     *   - If we have data we need to send it
     *     - on a pull because it was asked
     *     - on a push because it the push is the result of a pull
     *   - If we have not enough data but there is no more data
     *     - we need to emit the rest and finish
     *   - If there is not enough data we need to ask for more
     */
    private def handle(): Unit = {
      if( buffer.size >= chunkSize ) {
        emitChunk()
      } else if( buffer.size < chunkSize && isClosed(in) ) {
        emitChunk()
        completeStage()
      } else pull(in)
    }

    private def emitChunk(): Unit = {
      if( buffer.nonEmpty ){
        val (chunk, nextBuffer) = buffer.splitAt(chunkSize)
        buffer = nextBuffer
        push(out, chunk)
      }
    }
  }
}
