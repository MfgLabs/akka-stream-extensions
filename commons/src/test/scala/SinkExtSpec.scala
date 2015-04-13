package com.mfglabs.stream

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorFlowMaterializerSettings, ActorFlowMaterializer}
import com.mfglabs.stream.ExecutionContextForBlockingOps
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Minutes, Span}
import org.scalatest.{Matchers, FlatSpec}

class SinkExtSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val mat = ActorFlowMaterializer(ActorFlowMaterializerSettings(system).withInputBuffer(16, 16))
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(3, Minutes), interval = Span(20, Millis))

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val ecBlocking = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  "collect" should "consume a stream and return the elements as a list" in {
    val elems = for (i <- 1 to 100) yield i

    val res = Source(elems).runWith(SinkExt.collect).futureValue

    res shouldEqual elems.toSeq
  }


}
