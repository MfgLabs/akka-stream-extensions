package com.mfglabs.stream

import akka.stream.scaladsl.Sink

trait SinkExt {
  /**
   * Consume a stream as a Seq.
   * @tparam T
   * @return
   */
  def collect[T] = Sink.fold[IndexedSeq[T], T](Vector.empty)(_ :+ _)
}

object SinkExt extends SinkExt

