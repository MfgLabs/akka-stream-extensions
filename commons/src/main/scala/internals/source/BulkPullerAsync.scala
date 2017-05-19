package com.mfglabs.stream.internals.source

import akka.pattern.pipe
import akka.actor._
import akka.stream.actor.ActorPublisher

import scala.concurrent.Future
import scala.concurrent.duration._

private[stream] abstract class GenericBulkPullerAsync[A, S](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)])
                                           extends ActorPublisher[A] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._
  implicit val ec = context.dispatcher

  case object Pull

  var s: S
  def onFutureFailure(err: Throwable, currentPosition: Long, downstreamDemandBeforeFut: Int, buffer: Seq[A]): Unit

  def receive = waitingForDownstreamReq(offset, Vector.empty, stopAfterBuf = false)

  def waitingForDownstreamReq(currentPosition: Long, buffer: Seq[A], stopAfterBuf: Boolean): Receive = {
    case Request(_) | Pull =>
      if (totalDemand > 0 && isActive) {
        nextElements(currentPosition, totalDemand.toInt, buffer, stopAfterBuf).pipeTo(self)
        context.become(waitingForFut(currentPosition, buffer, totalDemand.toInt))
      }

    case Cancel => context.stop(self)
  }

  def waitingForFut(currentPosition: Long, buffer: Seq[A], downstreamDemandBeforeFut: Int): Receive = {
    case (as: Seq[A], stop: Boolean) =>
      val (requestedAs, keep) = (buffer ++ as).splitAt(downstreamDemandBeforeFut.toInt)
      requestedAs.foreach(onNext)
      if (keep.isEmpty && stop) {
        onComplete()
      } else {
        if (totalDemand > 0) self ! Pull
        context.become(waitingForDownstreamReq(currentPosition + as.length, keep, stop))
      }

    case Request(_) | Pull => // ignoring until we receive the future response

    case Status.Failure(err) => onFutureFailure(err, currentPosition, downstreamDemandBeforeFut, buffer)

    case Cancel => context.stop(self)
  }

  def nextElements(currentPosition: Long, n: Int, buffer: Seq[A], stopAfterBuf: Boolean): Future[(Seq[A], Boolean)] =
    if (buffer.nonEmpty && (buffer.size >= n || stopAfterBuf)) Future.successful((Seq.empty, stopAfterBuf))
    else f(currentPosition, n)
}


class BulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) extends GenericBulkPullerAsync[A, Unit](offset)(f) {
  override var s = ()

  override def onFutureFailure(err: Throwable, currentPosition: Long, downstreamDemandBeforeFut: Int, buffer: Seq[A]): Unit = {
    context.become(waitingForDownstreamReq(currentPosition, Seq.empty, stopAfterBuf = false))
    if (totalDemand > 0) self ! Pull
    onError(err)
  }
}

object BulkPullerAsync {
  def props[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) = Props(new BulkPullerAsync[A](offset)(f))
}


class BulkPullerAsyncWithErrorMgt[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)])
                                    (stopOnError: (Throwable, Int) => Boolean) extends GenericBulkPullerAsync[A, Int](offset)(f) {
  override var s = 0

  override def onFutureFailure(err: Throwable, currentPosition: Long, downstreamDemandBeforeFut: Int, buffer: Seq[A]): Unit = {
    s = s + 1
    if (stopOnError(err, s)) {
      onError(err)
    }
    else {
      if (totalDemand > 0) self ! Pull
      context.become(waitingForDownstreamReq(s.toLong, Seq.empty, stopAfterBuf = false))
    }
  }
}

object BulkPullerAsyncWithErrorMgt {
  def props[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)])(contFn: (Throwable, Int) => Boolean) =
    Props(new BulkPullerAsyncWithErrorMgt[A](offset)(f)(contFn))
}


class BulkPullerAsyncWithErrorExpBackoff[A](offset: Long, maxRetryDuration: FiniteDuration, retryMinInterval: FiniteDuration)
                                           (f: (Long, Int) => Future[(Seq[A], Boolean)])
                                           extends GenericBulkPullerAsync[A, (Int, FiniteDuration)](offset)(f) {
  override var s = (0, Duration.Zero)

  override def onFutureFailure(err: Throwable, currentPosition: Long, downstreamDemandBeforeFut: Int, buffer: Seq[A]): Unit = {
    val (retryIdx, retryDuration) = s
    val expBackoff = nextExpBackoff(retryIdx)
    val total = retryDuration + expBackoff

    log.debug(s"RetryDuration: $retryDuration ExpBackoff: $expBackoff")

    if (total > maxRetryDuration) {
      log.debug(s"Reached max duration $total > $maxRetryDuration")
      onError(err)
    } else {
      log.debug(s"Scheduling retry in $expBackoff")
      s = (retryIdx + 1, total)
      context.become(waitingForDownstreamReq(currentPosition, Seq.empty, stopAfterBuf = false))
      context.system.scheduler.scheduleOnce(expBackoff, self, Pull)
      ()
    }
  }

  def nextExpBackoff(i: Int): FiniteDuration = retryMinInterval.mul(math.pow(2, i.toDouble).toLong)
}

object BulkPullerAsyncWithErrorExpBackoff {
  def props[A](offset: Long, maxRetryDuration: FiniteDuration, retryMinInterval: FiniteDuration)
              (f: (Long, Int) => Future[(Seq[A], Boolean)]) =
    Props(new BulkPullerAsyncWithErrorExpBackoff[A](offset, maxRetryDuration, retryMinInterval)(f))
}
