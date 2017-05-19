package com.mfglabs.stream

import java.io.{FileInputStream, InputStream, File}
import java.util.zip.GZIPInputStream

import akka.NotUsed
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.actor.ActorRef
import com.mfglabs.stream.internals.source.{UnfoldPullerAsync, BulkPullerAsync, BulkPullerAsyncWithErrorMgt, BulkPullerAsyncWithErrorExpBackoff}

import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

trait SourceExt {
  val defaultChunkSize = 8 * 1024

  /**
   * Create a Source from a zipped input stream and unzip it on the fly.
   * @param is source input stream
   * @param maxChunkSize max size of the chunks that the source will emit (in bytes).
   * @param ec ec that will be used for the input stream's blocking operations
   * @return
   */
  def fromGZIPStream(is: InputStream, maxChunkSize: Int = defaultChunkSize): Source[ByteString, Future[akka.stream.IOResult]] =
    StreamConverters.fromInputStream(() => new GZIPInputStream(is), maxChunkSize)

  /**
   * Create a Source from a File.
   * @param f file
   * @param maxChunkSize max size of stream chunks in bytes
   * @param ec ec that will be used for the input stream's blocking operations
   * @return
   */
  def fromFile(f: File, maxChunkSize: Int = defaultChunkSize): Source[ByteString, Future[akka.stream.IOResult]] =
    StreamConverters.fromInputStream(() => new FileInputStream(f), maxChunkSize)

  /**
   * Create a Source from a zip File and unzip it on the fly.
   * Note: Akka Stream RC1 introduced a built-in way to create a Source from an InputStream, therefore this version is deprecated.
   * @param f file
   * @param maxChunkSize max size of the chunks that the source will emit (in bytes).
   * @param ec
   * @return
   */
  def fromGZIPFile(f: File, maxChunkSize: Int = defaultChunkSize): Source[ByteString, Future[akka.stream.IOResult]] =
    StreamConverters.fromInputStream(() => new FileInputStream(f), maxChunkSize)

  /**
   * Create a source that calls the f function each time that downstream requests more elements.
   * @param offset initial offset
   * @param f pulling function that takes as first argument (offset + nb of already pushed elements into downstream) and
   *          as second argument the maximum number of elements that can be currently pushed downstream.
   *          Returns a sequence of elements to push, and a stop boolean (true means that this is the end of the stream)
   * @tparam A
   * @return
   */
  def bulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]): Source[A, ActorRef] =
    Source.actorPublisher(BulkPullerAsync.props(offset)(f))

  /**
   * Create a source that calls the f function each time that downstream requests more elements
   * and retries a max number of times in case of errors.
   *
   * @param offset initial offset
   * @param maxRetries maximum number of retries in case of error in the future returned by the pulling function f
   * @param f pulling function that takes as first argument (offset + nb of already pushed elements into downstream) and
   *          as second argument the maximum number of elements that can be currently pushed downstream.
   *          Returns a sequence of elements to push, and a stop boolean (true means that this is the end of the stream)
   * @tparam A
   * @return
   */
  def bulkPullerAsyncWithMaxRetries[A](offset: Long, maxRetries: Int)(f: (Long, Int) => Future[(Seq[A], Boolean)]): Source[A, ActorRef] =
    bulkPullerAsyncWithErrorMgt(offset)(f, {
      case (NonFatal(_), n) if n <= maxRetries => false
      case _ => true
    })

  /**
   * Create a source that calls the f function each time that downstream requests more elements
   * and in case of error in the Future, it calls your continuation error function (true to continue, false to stop).
   *
   * @param offset initial offset
   * @param f pulling function that takes as first argument (offset + nb of already pushed elements into downstream) and
   *          as second argument the maximum number of elements that can be currently pushed downstream.
   *          Returns a sequence of elements to push, and a stop boolean (true means that this is the end of the stream)
   * @param stopOnErr stop on error function called in case of error in the future returned by the pulling function f
   *                  (return true to stop the stream in failure & false to ignore the error and continue)
   * @tparam A
   * @return
   */
  def bulkPullerAsyncWithErrorMgt[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)], stopOnErr: (Throwable, Int) => Boolean): Source[A, ActorRef] =
    Source.actorPublisher(BulkPullerAsyncWithErrorMgt.props(offset)(f)(stopOnErr))

  /**
   * Create a source that calls the f function each time that downstream requests more elements
   * and in case of error in the Future, it calls your continuation error function (true to continue, false to stop).
   *
   * @param offset initial offset
   * @param f pulling function that takes as first argument (offset + nb of already pushed elements into downstream) and
   *          as second argument the maximum number of elements that can be currently pushed downstream.
   *          Returns a sequence of elements to push, and a stop boolean (true means that this is the end of the stream)
   * @tparam A
   * @return
   */
  def bulkPullerAsyncWithErrorExpBackoff[A](offset: Long, maxRetryDuration: FiniteDuration, retryMinInterval: FiniteDuration)(f: (Long, Int) => Future[(Seq[A], Boolean)]): Source[A, ActorRef] =
    Source.actorPublisher(BulkPullerAsyncWithErrorExpBackoff.props(offset, maxRetryDuration, retryMinInterval)(f))

  /**
   * Create a source that calls the f function each time that downstream requests more elements.
   * @param zero
   * @param f pulling unfold function that takes a state B and produce optionally an element to push to downstream. It produces
   *          a new state b if we want the stream to continue or no new state if we want the stream to end.
   * @return
   */
  @deprecated("Since 0.11.0","Source now support unfoldAsync")
  def unfoldPullerAsync[A, B](zero: => B)(f: B => Future[(Option[A], Option[B])]): Source[A, ActorRef] =
    Source.actorPublisher(UnfoldPullerAsync.props[A, B](zero)(f))

  /**
   * Create a source from the result of a Future redeemed when the stream is materialized.
   *
   * @param futB the future seed
   * @param f the function producing the source from the seed
   * @tparam A
   * @tparam B
   * @return
   */
  def seededLazyAsync[A, B, M](futB: => Future[B])(f: B => Source[A, M]): Source[A, NotUsed] =
    singleLazyAsync(futB).map(f).flatMapConcat(identity)

  /**
   * Create a source from a Lazy Async value that will be evaluated only when the stream is materialized.
   *
   * @param fut
   * @tparam A
   * @return
   */
  def singleLazyAsync[A](fut: => Future[A]): Source[A, NotUsed] = singleLazy(fut).mapAsync(1)(identity)

  /**
   * Create a source from a Lazy Value that will be evaluated only when the stream is materialized.
   *
   * @param a
   * @tparam A
   * @return
   */
  def singleLazy[A](a: => A): Source[A, NotUsed] = Source.single(() => a).map(_())

  /**
   * Create an infinite source of the same Async Lazy value evaluated only when the stream is materialized.
   *
   * @param fut
   * @tparam A
   * @return
   */
  def constantLazyAsync[A](fut: => Future[A]): Source[A, ActorRef] = constantLazy(fut).mapAsync(1)(identity)

  /**
   * Create an infinite source of the same Lazy value evaluated only when the stream is materialized.
   *
   * @param a
   * @tparam A
   * @return
   */
  def constantLazy[A](a: => A): Source[A, ActorRef] =
    unfoldPullerAsync(a) { evaluatedA => Future.successful(Some(evaluatedA) -> Some(evaluatedA)) }
}

object SourceExt extends SourceExt
