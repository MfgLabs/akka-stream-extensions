package com.mfglabs.stream
package extensions.elasticsearch

import akka.actor.ActorSystem
import akka.stream._
import org.elasticsearch.index.query.QueryBuilders
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Minutes, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpec}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

import org.elasticsearch.common.settings.Settings
import org.elasticsearch.node.Node

class ElasticExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  implicit override val patienceConfig = PatienceConfig(timeout = Span(1, Minutes), interval = Span(100, Millis))

  implicit val as = ActorSystem()
  implicit val fm = ActorMaterializer()
  implicit val blockingEc = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  val settings = Settings.builder()
    .put("path.data", "target/elasticsearch-data")
    .put("path.home", "/")
    .put("transport.type", "local")
    .put("http.enabled", false)
    .build();

  lazy val node = new Node(settings).start();
  implicit lazy val client = node.client()

  val index = "test"
  val `type` = "type"

  "EsStream" should "execute a query a get the result as a stream" in {
    Try(client.admin.indices().prepareDelete(index).get())

    val toIndex = for (i <- 1 to 5002) yield (i, s"""{i: $i}""")
    toIndex.foreach { case (i, json) =>
      client.prepareIndex(index, `type`).setSource(json).setId(i.toString).get()
    }

    client.admin.indices.prepareRefresh(index).get() // to be sure that the data is indexed

    val res = EsStream.queryAsStream(QueryBuilders.matchAllQuery(), index, `type`, 1 minutes, 50)
      .runWith(SinkExt.collect)
      .futureValue

    res.sorted shouldEqual toIndex.map(_._2).sorted
  }

  override def afterAll(): Unit = {
    client.close()
    node.close()
  }

}
