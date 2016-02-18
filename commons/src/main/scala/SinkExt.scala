package com.mfglabs.stream

import akka.stream.scaladsl.Sink

trait SinkExt {
  /**
   * Consume a stream as a Seq.
   * @tparam T
   * @return
   */
  @deprecated("Sink.seq has now been aded to Akka Stream to collect stream elements as a collection, so this version is deprecated.", "")
  def collect[T] = Sink.fold[IndexedSeq[T], T](Vector.empty)(_ :+ _)
}

object SinkExt extends SinkExt