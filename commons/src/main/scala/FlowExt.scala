package com.mfglabs.stream

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.concurrent._
import scala.concurrent.duration._
import akka.stream.scaladsl._

trait FlowExt {

  /**
   * Create a Flow  which debounce messages with similar hashes
   */
  def debounce[A](per: FiniteDuration, toHash: A => String): Flow[A, A, NotUsed] = {
    Flow[A]
      .via(Debounce(per, toHash))
      .collect { case Debounced.Ok(elem) => elem }
  }

  /**
   * Create a Flow whose creation depends on the first element of the upstream.
   * @param includeHeadInUpStream true if we want the first element of the upstream to be included in the dowstream.
   * @param f takes the first element of upstream in input and returns the resulting flow
   * @tparam A
   * @tparam B
   * @tparam M
   * @return the flow returned by f
   */
  def withHead[A, B, M](includeHeadInUpStream: Boolean)(f: A => Flow[A, B, M]): Flow[A, B, NotUsed] = {
    Flow[A]
      .prefixAndTail(1)
      .map {
        case (Seq(), _) => Source.empty
        case (head +: _, tailStream) =>
          if (includeHeadInUpStream) Source.combine(Source.single(head), tailStream)(Concat(_)).via(f(head))
          else tailStream.via(f(head))
      }
      .flatMapConcat(identity)
  }

  /**
   * Zip a stream with the indices of its elements.
   * @return
   */
  def zipWithIndex[A]: Flow[A, (A, Long), NotUsed] = {
    withHead(includeHeadInUpStream = false) { head =>
      Flow[A].scan((head, 0L)) { case ((_, n), el) => (el, n + 1) }
    }
  }

  @deprecated("Since 0.12.0", "Use Framing.delimiter")
  def rechunkByteStringBySeparator(
    separator         : ByteString,
    maximumChunkBytes : Int
  ): Flow[ByteString, ByteString, NotUsed] = {
    Flow[ByteString].via(Framing.delimiter(separator, maximumChunkBytes, allowTruncation = true))
  }


  /**
   * Limit downstream rate to one element every 'interval' by applying back-pressure on upstream.
   * @param interval time interval to send one element downstream
   * @tparam A
   * @return
   */
  def rateLimiter[A](interval: FiniteDuration): Flow[A, A, NotUsed] = {
    case object Tick

    val flow = Flow.fromGraph( GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val rateLimiter = Source.tick(0 second, interval, Tick)

      val zip = builder.add(Zip[A, Tick.type]())

      rateLimiter ~> zip.in1

      FlowShape(zip.in0, zip.out)
    }).map(_._1)

    // We need to limit input buffer to 1 to guarantee the rate limiting feature
    flow.withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
  }

  /**
   * Rechunk a stream of bytes according to a chunk size.
   * @param chunkSize the new chunk size
   * @return
   */
  def rechunkByteStringBySize(chunkSize: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new Chunker(chunkSize))

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
  def customStatefulProcessor[A, B, C](zero: => B)(
    f: (B, A) => (Option[B], IndexedSeq[C]),
    lastPushIfUpstreamEnds: B => IndexedSeq[C] = { _: B => IndexedSeq.empty }
  ): Flow[A, C, NotUsed] = Flow[A].via(new StatefulProcessor(zero, f, lastPushIfUpstreamEnds))

  /**
   * Unfold a stream with an user-defined function.
   * @param f take current elem, and return a seq of B elems with a stop boolean (true means that we want the stream to stop after sending
   *          the joined seq of B elems)
   * @tparam A
   * @tparam B
   * @return
   */
  def customStatelessProcessor[A, B](f: A => (IndexedSeq[B], Boolean)): Flow[A, B, NotUsed] = {
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
  def fold[A, B](zero: => B)(f: (B, A) => B): Flow[A, B, NotUsed] = {
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
  @deprecated("Since 0.12.0","Flow now support takeWhile")
  def takeWhile[A](f: A => Boolean): Flow[A, A, NotUsed] = Flow[A].takeWhile(f)

  /**
   * Zip a stream with a lazy future that will be evaluated only when the stream is materialized.
   * @param futB
   * @tparam A
   * @tparam B
   * @return
   */
  def zipWithConstantLazyAsync[A, B](futB: => Future[B]): Flow[A, (A, B), NotUsed] = {
    Flow.fromGraph( GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val zip = builder.add(Zip[A, B]())

      SourceExt.constantLazyAsync(futB) ~> zip.in1

      FlowShape(zip.in0, zip.out)
    })
  }

  /**
   * Repeat each element of the source 'nb' times.
   * @param nb the number of repetitions
   * @tparam A
   * @return
   */
  def repeatEach[A](nb: Int): Flow[A, A, NotUsed] = Flow[A].mapConcat(a => Vector.fill(nb)(a))

}

object FlowExt extends FlowExt
