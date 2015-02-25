package com.mfglabs.stream

import akka.stream.scaladsl.FoldSink

trait SinkExt {
  /**
   * consume a stream and return it as a list
   * @tparam T records type
   * @return a list of records
   */
  def collect[T] = FoldSink[Seq[T],T](Seq.empty[T])(_ :+ _)
}

object SinkExt extends SinkExt

