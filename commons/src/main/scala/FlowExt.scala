package com.mfglabs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString

import scala.collection.{GenTraversableLike, TraversableLike, IterableLike}
import scala.concurrent._
import scala.concurrent.duration._
import akka.stream.scaladsl._

trait FlowExt {

  /**
   * Create a Flow whose creation depends on the first element of the upstream.
   * @param includeHeadInUpStream true if we want the first element of the upstream to be included in the dowstream.
   * @param f takes the first element of upstream in input and returns the resulting flow
   * @tparam A
   * @tparam B
   * @tparam M
   * @return the flow returned by f
   */
  def withHead[A, B, M](includeHeadInUpStream: Boolean)(f: A => Flow[A, B, M]): Flow[A, B, Unit] = {
    Flow[A]
      .prefixAndTail(1)
      .map {
        case (Seq(), _) => Source.empty
        case (head +: _, tailStream) =>
          if (includeHeadInUpStream) Source.concat(Source.single(head), tailStream).via(f(head))
          else tailStream.via(f(head))
      }
      .flatten(FlattenStrategy.concat)
  }

  /**
   * Zip a stream with the indices of its elements.
   * @return
   */
  def zipWithIndex[A]: Flow[A, (A, Long), Unit] = {
    withHead(includeHeadInUpStream = false) { head =>
      Flow[A].scan((head, 0L)) { case ((_, n), el) => (el, n + 1) }
    }
  }

  /**
   * Rechunk of stream of bytes according to a separator
   * @param separator the separator to split the stream. For example ByteString("\n") to split a stream by lines.
   * @param maximumChunkBytes the maximum possible size of a split to send downstream (in bytes). If no separator is found
   *                          before reaching this limit, the stream fails.
   * @return
   */
  def rechunkByteStringBySeparator(separator: ByteString, maximumChunkBytes: Int): Flow[ByteString, ByteString, Unit] = {
    def stage = new PushPullStage[ByteString, ByteString] {
      private val separatorBytes = separator
      private val firstSeparatorByte = separatorBytes.head
      private var buffer = ByteString.empty
      private var nextPossibleMatch = 0

      override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
        buffer ++= chunk
        emitChunkOrPull(ctx)
      }

      override def onPull(ctx: Context[ByteString]): SyncDirective = emitChunkOrPull(ctx)

      private def emitChunkOrPull(ctx: Context[ByteString]): SyncDirective = {
        val possibleMatchPos = buffer.indexOf(firstSeparatorByte, from = nextPossibleMatch)
        if (possibleMatchPos == -1) {
          // No matching character, we need to accumulate more bytes into the buffer
          nextPossibleMatch = buffer.size
          pushIfLastChunkOrElsePull(ctx)
        } else if (possibleMatchPos + separatorBytes.size > buffer.size) {
          // We have found a possible match (we found the first character of the terminator
          // sequence) but we don't have yet enough bytes. We remember the position to
          // retry from next time.
          nextPossibleMatch = possibleMatchPos
          pushIfLastChunkOrElsePull(ctx)
        } else {
          if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size) == separatorBytes) {
            // Found a match
            val nextChunk = buffer.slice(0, possibleMatchPos)
            buffer = buffer.drop(possibleMatchPos + separatorBytes.size)
            nextPossibleMatch -= possibleMatchPos + separatorBytes.size
            ctx.push(nextChunk)
          } else {
            nextPossibleMatch += 1
            pushIfLastChunkOrElsePull(ctx)
          }
        }
      }

      private def pushIfLastChunkOrElsePull(ctx: Context[ByteString]) = {
        if (ctx.isFinishing) {
          if (buffer.isEmpty) {
            ctx.finish()
          } else {
            ctx.pushAndFinish(buffer) // last uncompleted line
          }
        }
        else {
          if (buffer.size > maximumChunkBytes)
            ctx.fail(new IllegalStateException(s"Read ${buffer.size} bytes " +
              s"which is more than $maximumChunkBytes without seeing a line terminator"))
          else
            ctx.pull()
        }
      }

      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = ctx.absorbTermination()
    }

    Flow[ByteString].transform(() => stage)
  }

  /**
   * Limit downstream rate to one element every 'interval' by applying back-pressure on upstream.
   * @param interval time interval to send one element downstream
   * @tparam A
   * @return
   */
  def rateLimiter[A](interval: FiniteDuration): Flow[A, A, Unit] = {
    case object Tick

    val flow = Flow() { implicit builder =>
      import FlowGraph.Implicits._

      val rateLimiter = Source.apply(0 second, interval, Tick)

      val zip = builder.add(Zip[A, Tick.type]())

      rateLimiter ~> zip.in1

      (zip.in0, zip.out.map(_._1).outlet)
    }

    // We need to limit input buffer to 1 to guarantee the rate limiting feature
    flow.withAttributes(OperationAttributes.inputBuffer(initial = 1, max = 1))
  }

  /**
   * Rechunk a stream of bytes according to a chunk size.
   * @param chunkSize the new chunk size
   * @return
   */
  def rechunkByteStringBySize(chunkSize: Int): Flow[ByteString, ByteString, Unit] = {
    def stage = new PushPullStage[ByteString, ByteString] {
      private var buffer = ByteString.empty

      override def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = {
        buffer ++= elem
        emitChunkOrPull(ctx)
      }

      override def onPull(ctx: Context[ByteString]): SyncDirective = emitChunkOrPull(ctx)

      private def emitChunkOrPull(ctx: Context[ByteString]): SyncDirective = {
        if (ctx.isFinishing) {
          if (buffer.isEmpty) {
            ctx.finish()
          } else if (buffer.length < chunkSize) {
            ctx.pushAndFinish(buffer)
          } else {
            val (emit, nextBuffer) = buffer.splitAt(chunkSize)
            buffer = nextBuffer
            ctx.push(emit)
          }
        } else {
          if (buffer.length < chunkSize) {
            ctx.pull()
          } else {
            val (emit, nextBuffer) = buffer.splitAt(chunkSize)
            buffer = nextBuffer
            ctx.push(emit)
          }
        }
      }

      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = ctx.absorbTermination()
    }

    Flow[ByteString].transform(() => stage)
  }

  /**
   * Fold and/or unfold the stream with an user-defined function.
   * @param zero initial state
   * @param f takes current state and current elem, returns a seq of C elements to push downstream and the next state b
   *          if we want the stream to continue (if no new state b, the stream ends).
   * @param lastPushIfUpstreamEnds if the upstream ends (before customStatefulProcessor decides to end the stream),
   *                               this function is called on the last b state and the resulting c elements
   *                               are pushed downstream as the last elements of the stream.
   * @return
   */
  def customStatefulProcessor[A, B, C](zero: => B)
                             (f: (B, A) => (Option[B], IndexedSeq[C]),
                              lastPushIfUpstreamEnds: B => IndexedSeq[C] = {_: B => IndexedSeq.empty}): Flow[A, C, Unit] = {
    def stage = new PushPullStage[A, C] {
      private var state: B = _
      private var buffer = Vector.empty[C]
      private var finishing = false

      override def onPush(elem: A, ctx: Context[C]): SyncDirective = {
        if (state == null) state = zero // to keep the laziness of zero
        f(state, elem) match {
          case (Some(b), cs) =>
            state = b
            buffer ++= cs
            emitChunkOrPull(ctx)
          case (None, cs) =>
            buffer ++= cs
            finishing = true
            emitChunkOrPull(ctx)
        }
      }

      override def onPull(ctx: Context[C]): SyncDirective = {
        if (state == null) state = zero // to keep the laziness of zero
        emitChunkOrPull(ctx)
      }

      private def emitChunkOrPull(ctx: Context[C]): SyncDirective = {
        if (finishing) { // customProcessor is ending
          buffer match {
            case Seq() => ctx.finish()
            case elem +: nextBuffer =>
              buffer = nextBuffer
              ctx.push(elem)
          }
        } else if (ctx.isFinishing) { // upstream ended
          buffer match {
            case Seq() =>
              lastPushIfUpstreamEnds(state) match {
                case Seq() => ctx.finish()
                case elem +: nextBuffer =>
                  finishing = true
                  buffer = nextBuffer.toVector
                  ctx.push(elem)
              }
            case elem +: nextBuffer =>
              buffer = nextBuffer
              ctx.push(elem)
          }
        } else {
          buffer match {
            case Seq() => ctx.pull()
            case elem +: nextBuffer =>
              buffer = nextBuffer
              ctx.push(elem)
          }
        }
      }

      override def onUpstreamFinish(ctx: Context[C]): TerminationDirective = ctx.absorbTermination()
    }

    Flow[A].transform(() => stage)
  }

  /**
   * Unfold a stream with an user-defined function.
   * @param f take current elem, and return a seq of B elems with a stop boolean (true means that we want the stream to stop after sending
   *          the joined seq of B elems)
   * @tparam A
   * @tparam B
   * @return
   */
  def customStatelessProcessor[A, B](f: A => (IndexedSeq[B], Boolean)): Flow[A, B, Unit] = {
    customStatefulProcessor[A, Unit, B](())(
      (_, a) => {
        val (bs, stop) = f(a)
        (if (stop) None else Some(()), bs)
      }
    )
  }

  /**
   * Fold the stream and push the last B to downstream when upstream finishes.
   * @param zero
   * @param f
   * @tparam A
   * @tparam B
   * @return
   */
  def fold[A, B](zero: => B)(f: (B, A) => B): Flow[A, B, Unit] = {
    customStatefulProcessor[A, B, B](zero)(
      (b, a) => (Some(f(b, a)), Vector.empty),
      b => Vector(b)
    )
  }

  /**
   * Consume the stream while condition is true.
   * @param f condition
   * @tparam A
   * @return
   */
  def takeWhile[A](f: A => Boolean): Flow[A, A, Unit] = {
    customStatelessProcessor { a =>
      if (!f(a)) (Vector.empty, true)
      else (Vector(a), false)
    }
  }

  /**
   * Zip a stream with a lazy future that will be evaluated only when the stream is materialized.
   * @param futB
   * @tparam A
   * @tparam B
   * @return
   */
  def zipWithConstantLazyAsync[A, B](futB: => Future[B])(implicit ec: ExecutionContext): Flow[A, (A, B), Unit] = {
    Flow() { implicit builder =>
      import FlowGraph.Implicits._

      val zip = builder.add(Zip[A, B]())

      SourceExt.constantLazyAsync(futB) ~> zip.in1

      (zip.in0, zip.out)
    }
  }

  /**
   * Repeat each element of the source 'nb' times.
   * @param nb the number of repetitions
   * @tparam A
   * @return
   */
  def repeatEach[A](nb: Int): Flow[A, A, Unit] = Flow[A].mapConcat(a => Vector.fill(nb)(a))

}

object FlowExt extends FlowExt
