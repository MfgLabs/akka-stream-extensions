package com.mfglabs.stream

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

/**
 * Inspired by https://doc.akka.io/docs/akka/2.5/stream/stream-cookbook.html#working-with-io
 */
class StatefulProcessor[A, B, C](
  zero  : => B,
  f     : (B, A) => (Option[B], IndexedSeq[C]),
  lastPushIfUpstreamEnds: B => IndexedSeq[C]
) extends GraphStage[FlowShape[A, C]] {

  val in = Inlet[A]("StatefulProcessor.in")
  val out = Outlet[C]("StatefulProcessor.out")

  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var state: B  = _
    private var buffer    = Vector.empty[C]
    private var finishing = false

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if( state == null ) state = zero // to keep the laziness of zero
        emitChunkOrPull()
      }
    })

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        if( state == null ) state = zero // to keep the laziness of zero

        val (newState, cs) = f(state, grab(in))

        newState match {
          case Some(s) => state = s
          case None    => finishing = true
        }

        buffer ++= cs
        emitChunkOrPull()
      }

      /**
       * We check out availaibility since a Pull might have been lost when closing.
       */
      override def onUpstreamFinish(): Unit = {
        if( isAvailable(out) ) emitChunkOrPull()
      }
    })

    def bufferFold(whenEmpty: => Unit)(f: C => Unit): Unit = {
      buffer match {
        case Seq() => whenEmpty
        case elem +: nextBuffer =>
          buffer = nextBuffer
          f(elem)
      }
    }

    /**
     * We check first :
     *   - if the customProcessor is ending
     *   - if upstream ended
     */
    private def emitChunkOrPull() = {
      if( finishing ){
        bufferFold { completeStage() }{ elem => push(out, elem) }
      } else if( isClosed(in) ){
        bufferFold {
          buffer = lastPushIfUpstreamEnds(state).toVector
          bufferFold { completeStage() } { elem =>
            finishing = true
            push(out, elem)
          }
        }{ elem => push(out, elem) }
      } else bufferFold { pull(in) } { elem => push(out, elem) }
    }
  }
}
