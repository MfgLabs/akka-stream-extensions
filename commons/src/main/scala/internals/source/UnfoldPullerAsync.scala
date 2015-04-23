package com.mfglabs.stream.internals.source

import akka.pattern.pipe
import akka.actor.{Props, Status, ActorLogging}
import akka.stream.actor.ActorPublisher

import scala.concurrent.Future

class UnfoldPullerAsync[A, B](zero: => B)(f: B => Future[(Option[A], Option[B])]) extends ActorPublisher[A] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._
  implicit val ec = context.dispatcher

  def receive = waitingForDownstreamReq(zero)

  case object Pull

  def waitingForDownstreamReq(s: B): Receive = {
    case Request(_) | Pull =>
      if (totalDemand > 0 && isActive) {
        f(s).pipeTo(self)
        context.become(waitingForFut(s))
      }

    case Cancel => context.stop(self)
  }

  def waitingForFut(s: B): Receive = {
    case (maybeA: Option[A], maybeB: Option[B]) =>
      maybeA.foreach(onNext)
      maybeB match {
        case Some(b) =>
          if (totalDemand > 0) self ! Pull
          context.become(waitingForDownstreamReq(b))
        case None =>
          onComplete()
      }

    case Request(_) | Pull => // ignoring until we receive the future response

    case Status.Failure(err) =>
      context.become(waitingForDownstreamReq(s))
      onError(err)

    case Cancel => context.stop(self)
  }

}

object UnfoldPullerAsync {
  def props[A, B](zero: => B)(f: B => Future[(Option[A], Option[B])]) = Props(new UnfoldPullerAsync[A, B](zero)(f))
}