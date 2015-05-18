package com.mfglabs.stream.internals.source

import akka.pattern.pipe
import akka.actor.{Props, Status, ActorLogging}
import akka.stream.actor.ActorPublisher

import scala.concurrent.Future

class BulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) extends ActorPublisher[A] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._
  implicit val ec = context.dispatcher

  def receive = waitingForDownstreamReq(offset, Seq.empty, stopAfterBuf = false)

  case object Pull

  def waitingForDownstreamReq(s: Long, buf: Seq[A], stopAfterBuf: Boolean): Receive = {
    case Request(_) | Pull =>
      if (totalDemand > 0 && isActive) {
        nextElements(s, totalDemand.toInt, buf, stopAfterBuf).pipeTo(self)
        context.become(waitingForFut(s, buf, totalDemand))
      }

    case Cancel => context.stop(self)
  }

  private def nextElements(s: Long, n: Int, buf: Seq[A], stopAfterBuf: Boolean): Future[(Seq[A], Boolean)] =
    if (buf.nonEmpty && buf.size >= n) Future.successful((Seq.empty, stopAfterBuf))
    else f(s, n) map { r ⇒ (r._1, r._2) }

  def waitingForFut(s: Long, buf: Seq[A], beforeFutDemand: Long): Receive = {
    case (as: Seq[A], stop: Boolean) =>
      val (requestedAs, keep) = (buf ++ as).splitAt(beforeFutDemand.toInt)
      requestedAs.foreach(onNext)
      if (keep.isEmpty && stop) {
        onComplete()
      } else {
        if (totalDemand > 0) self ! Pull
        context.become(waitingForDownstreamReq(s + as.length, keep, stop))
      }

    case Request(_) | Pull => // ignoring until we receive the future response

    case Status.Failure(err) =>
      context.become(waitingForDownstreamReq(s, Seq.empty, stopAfterBuf = false))
      onError(err)

    case Cancel => context.stop(self)
  }

}

object BulkPullerAsync {
  def props[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) = Props(new BulkPullerAsync[A](offset)(f))
}