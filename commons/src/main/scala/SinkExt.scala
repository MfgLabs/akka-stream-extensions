package com.mfglabs.stream

import akka.stream.scaladsl.Sink


trait SinkExt {
  /**
   * Consume a stream and return it as a List.
   * @tparam T
   * @return
   */
  def collect[T] = Sink.fold[Seq[T],T](Seq.empty[T])(_ :+ _)
}

object SinkExt extends SinkExt

