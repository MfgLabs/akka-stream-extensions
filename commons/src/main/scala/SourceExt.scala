package com.mfglabs.stream

import java.io.{BufferedInputStream, FileInputStream, InputStream, File}
import java.util.zip.GZIPInputStream

import akka.stream.FlattenStrategy
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.mfglabs.stream.internals.source.{UnfoldPullerAsync, BulkPullerAsync}

import scala.concurrent._

case class ExecutionContextForBlockingOps(value: ExecutionContext) extends AnyVal

trait SourceExt {
  val defaultChunkSize = 8 * 1024

  private[stream] def readFromStream(is: InputStream, maxChunkSize: Int): Option[ByteString] = {
    val buffer = new Array[Byte](maxChunkSize)
    val bytesRead = is.read(buffer)
    bytesRead match {
      case -1 => None
      case `maxChunkSize` => Some(ByteString(buffer))
      case read => Some(ByteString.fromArray(buffer, 0, read))
    }
  }

  def fromStream(is: InputStream, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString] = {
    bulkPullerAsync[ByteString](0) { (counter, demand) =>
      Future {
        val fulfillments = Vector.fill(demand)(readFromStream(is, maxChunkSize)).flatten
        val stop = (fulfillments.size != demand)
        (fulfillments, stop)
      }(ec.value)
    }
  }

  def fromGZIPStream(is: InputStream, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString] =
    fromStream(new GZIPInputStream(is), maxChunkSize)(ec)

  def fromFile(f: File, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString] =
    fromStream(new FileInputStream(f), maxChunkSize)(ec)

  def fromGZIPFile(f: File, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString] =
    fromGZIPStream(new FileInputStream(f), maxChunkSize)(ec)

  /**
   * Helper to create a source that calls the f function each time that downstream requests more elements.
   * @param offset initial offset
   * @param f pulling function that takes as first argument (offset + nb of already pushed elements into downstream) and
   *          as second argument the maximum number of elements that can be currently pushed downstream.
   *          Returns a sequence of elements to push, and a stop boolean (true means that this is the end of the stream)
   * @tparam A
   * @return Pulling source
   */
  def bulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]): Source[A] =
    Source[A](BulkPullerAsync.props(offset)(f))

  /**
   * Helper to create a source that calls the f function each time that downstream requests more elements.
   * @param zero
   * @param f pulling unfold function that takes a state B and produce optionally an element to push to downstream. It produces
   *          a new state b if we want the stream to continue or no new state if we want the stream to end.
   * @return Pulling source
   */
  def unfoldPullerAsync[A, B](zero: => B)(f: B => Future[(Option[A], Option[B])]): Source[A] =
    Source[A](UnfoldPullerAsync.props[A, B](zero)(f))

  /**
   * Create a source from the result of a Future redeemed when the stream is materialized.
   *
   * @param futB the future seed
   * @param f the function producing the source from the seed
   * @tparam A
   * @tparam B
   * @return
   */
  def seededLazyAsync[A, B](futB: => Future[B])(f: B => Source[A]): Source[A] =
    singleLazyAsync(futB).map(f).flatten(FlattenStrategy.concat)

  /**
   * Create a source from a Lazy Async value that will be evaluated only when the stream is materialized.
   *
   * @param fut
   * @tparam A
   * @return
   */
  def singleLazyAsync[A](fut: => Future[A]): Source[A] = singleLazy(fut).mapAsync(identity)

  /**
   * Create a source from a Lazy Value that will be evaluated only when the stream is materialized.
   *
   * @param a
   * @tparam A
   * @return
   */
  def singleLazy[A](a: => A): Source[A] = Source.single(() => a).map(_())

  /**
   * Create an infinite source of the same Async Lazy value evaluated only when the stream is materialized.
   *
   * @param fut
   * @tparam A
   * @return
   */
  def constantLazyAsync[A](fut: => Future[A]): Source[A] = constantLazy(fut).mapAsync(identity)

  /**
   * Create an infinite source of the same Lazy value evaluated only when the stream is materialized.
   *
   * @param a
   * @tparam A
   * @return
   */
  def constantLazy[A](a: => A): Source[A] = 
    unfoldPullerAsync(a)(evaluatedA => Future.successful(Some(evaluatedA), Some(evaluatedA)))
}

object SourceExt extends SourceExt