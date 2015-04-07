package com.mfglabs.stream

import akka.stream.{OverflowStrategy, FlattenStrategy}
import akka.stream.stage._
import akka.util.ByteString

import scala.collection.{GenTraversableLike, TraversableLike, IterableLike}
import scala.concurrent._
import scala.concurrent.duration._
import akka.stream.scaladsl._

trait FlowExt {

  /**
   * perform an unordered map async with a bounded number of futures running concurrently
   * @param maxConcurrency
   * @param f
   * @tparam A
   * @tparam B
   * @return Bounded map flow
   */
  def mapAsyncUnorderedWithBoundedConcurrency[A, B](maxConcurrency: Int)(f: A => Future[B]): Flow[A, B, Unit] =
    Flow[A].section(OperationAttributes.inputBuffer(initial = maxConcurrency, max = maxConcurrency)) { sectionFlow =>
      sectionFlow.mapAsyncUnordered(f).buffer(maxConcurrency, OverflowStrategy.backpressure)
    }

  /**
   * perform an ordered map async with a bounded number of futures running concurrently
   * @param maxConcurrency
   * @param f
   * @tparam A
   * @tparam B
   * @return Bounded map flow
   */
  def mapAsyncWithBoundedConcurrency[A, B](maxConcurrency: Int)(f: A => Future[B]): Flow[A, B, Unit] =
    Flow[A].section(OperationAttributes.inputBuffer(initial = maxConcurrency, max = maxConcurrency)) { sectionFlow =>
      sectionFlow.mapAsync(f).buffer(maxConcurrency, OverflowStrategy.backpressure)
    }

  /**
   * perform a map async with a guarantee on the ordering of the running of futures. It can for instance be used to guarantee
   * the ordering of side-effecting futures.
   * @param f
   * @tparam A
   * @tparam B
   * @return Bounded map flow
   */
  def mapAsyncWithOrderedSideEffect[A, B](f: A => Future[B]): Flow[A, B, Unit] = mapAsyncWithBoundedConcurrency(1)(f)

  /**
   * Helper to create a flow whose creation depends on the first element of the upstream.
   * @param f
   * @tparam A
   * @tparam B
   * @return
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
   * add a sequence id to each record
   * @return a stream of strings
   */
  def zipWithIndex[A] : Flow[A, (A, Long), Unit] = {
    withHead(includeHeadInUpStream = false) { head =>
      Flow[A].scan((head, 0L)) { case ((_, n), el) => (el, n + 1) }
    }
  }

  /**
   * transform a stream of bytes into a stream of strings, split by line
   * @return a stream of strings
   */
  def rechunkByteStringBySeparator(separator: ByteString = ByteString("\n"),
                                   maximumChunkBytes: Int = Int.MaxValue): Flow[ByteString, ByteString, Unit] = {
    def stage = new PushPullStage[ByteString, ByteString] {
      private val separatorBytes = separator
      private val firstSeparatorByte = separatorBytes.head
      private var buffer = ByteString.empty
      private var nextPossibleMatch = 0

      override def onPush(chunk: ByteString, ctx: Context[ByteString]): Directive = {
        buffer ++= chunk
        emitChunkOrPull(ctx)
      }

      override def onPull(ctx: Context[ByteString]): Directive = emitChunkOrPull(ctx)

      private def emitChunkOrPull(ctx: Context[ByteString]): Directive = {
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
   * Limit downstream rate to one element every 'interval' by applying back-pressure on upstream
   * @param interval
   * @tparam A
   * @return A flow that limit the rate of the stream
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
    Flow[A].section(OperationAttributes.inputBuffer(initial = 1, max = 1)) { sectionFlow =>
      sectionFlow.via(flow)
    }
  }

  /**
   * Rechunk a stream of bytes with a new chunk size (example of StatefulStage version, do not delete)
   */
//  def rechunkByteString(chunkSize: Int): Flow[ByteString, ByteString] = {
//    val stage = new StatefulStage[ByteString, ByteString] {
//      private var buffer = ByteString.empty
//
//      override def initial = new State {
//        override def onPush(elem: ByteString, ctx: Context[ByteString]): Directive = {
//          buffer ++= elem
//          val chunks = buffer.grouped(chunkSize).toVector
//          chunks.lastOption match {
//            case Some(lastChunk) if lastChunk.length < chunkSize =>
//              buffer = lastChunk
//              emit(chunks.dropRight(1).toIterator, ctx)
//            case Some(lastChunk) if lastChunk.length == chunkSize =>
//              buffer = ByteString.empty
//              emit(chunks.toIterator, ctx)
//            case None =>
//              emit(Iterator.empty, ctx)
//          }
//        }
//
//        override def onPull(ctx: Context[ByteString]): Directive = {
//          if (ctx.isFinishing) {
//            emitAndFinish(buffer.grouped(chunkSize), ctx)
//          } else {
//            ctx.pull()
//          }
//        }
//      }
//
//      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = ctx.absorbTermination()
//    }
//
//    Flow[ByteString].transform(() => stage)
//  }

  /**
   * Rechunk a stream of bytes with a new chunk size
   */
  def rechunkByteStringBySize(chunkSize: Int): Flow[ByteString, ByteString, Unit] = {
    def stage = new PushPullStage[ByteString, ByteString] {
      private var buffer = ByteString.empty

      override def onPush(elem: ByteString, ctx: Context[ByteString]): Directive = {
        buffer ++= elem
        emitChunkOrPull(ctx)
      }

      override def onPull(ctx: Context[ByteString]): Directive = emitChunkOrPull(ctx)

      private def emitChunkOrPull(ctx: Context[ByteString]): Directive = {
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
   * Fold and/or unfold the stream with an user-defined function
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

      override def onPush(elem: A, ctx: Context[C]): Directive = {
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

      override def onPull(ctx: Context[C]): Directive = {
        if (state == null) state = zero // to keep the laziness of zero
        emitChunkOrPull(ctx)
      }

      private def emitChunkOrPull(ctx: Context[C]): Directive = {
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
   * Fold the stream and push the last B to downstream when upstream finishes
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
   * Take the stream while condition is false.
   * @param f
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
   * A flow that repeats each element of the source 'nb' times
   * @param nb the number of repetitions
   * @tparam A
   * @return
   */
  def repeatEach[A](nb: Int): Flow[A, A, Unit] = Flow[A].mapConcat(a => Vector.fill(nb)(a))

}

object FlowExt extends FlowExt
