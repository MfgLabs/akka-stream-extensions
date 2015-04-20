package com.mfglabs.stream
package extensions.elasticsearch

import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.node.NodeBuilder
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Minutes, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpec}

import scala.concurrent._
import scala.concurrent.duration._

class PostgresExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  implicit val as = ActorSystem()
  implicit val fm = ActorFlowMaterializer()
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(1, Minutes), interval = Span(100, Millis))
  implicit val blockingEc = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  "EsStream" should "execute a query a get the result as a stream" in {
    implicit val client = new NodeBuilder().build().start().client()

    val toIndex = for (i <- 1 to 10000) yield s"""{i: $i}"""
    toIndex.foreach { json =>
      client.prepareIndex("test", "type").setSource(json).get()
    }

    client.admin.indices.prepareRefresh().get() // to be sure that the data is indexed

    val res = EsStream.queryAsStream(QueryBuilders.matchAllQuery(), "test", "type", 1 minutes, 50)
      .runWith(SinkExt.collect)
      .futureValue

    res.sorted shouldEqual toIndex.toSeq.sorted

    client.close()
  }

}