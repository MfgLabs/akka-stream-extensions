package com.mfglabs.stream

import akka.stream.scaladsl._
import akka.testkit._

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.SpanSugar._

class DebounceSpec extends WordSpec with Matchers with ScalaFutures with BeforeAndAfterAll{
  import Debounced._

  implicit val system       = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  def fd(span: org.scalatest.time.Span): scala.concurrent.duration.FiniteDuration = {
    new scala.concurrent.duration.FiniteDuration(span.length, span.unit)
  }

  "DebounceStage" should {
    "debounce" in {
      val probe = TestProbe()
      val ref   = Source
        .actorRef[Int](Int.MaxValue, akka.stream.OverflowStrategy.fail)
        .via(Debounce(1.second, _.toString))
        .to(Sink.actorRef(probe.ref, "completed"))
        .run

      ref ! 5
      probe.expectMsg(100.millis, Ok(5))

      ref ! 5
      probe.expectMsg(100.millis, Ko(5))

      ref ! 6
      probe.expectMsg(100.millis, Ok(6))

      ref ! 5
      probe.expectMsg(100.millis, Ko(5))

      Thread.sleep(1100)

      ref ! 5
      probe.expectMsg(100.millis, Ok(5))
    }
  }

  override def afterAll = {
    materializer.shutdown
    system.terminate().futureValue
    ()
  }
}
