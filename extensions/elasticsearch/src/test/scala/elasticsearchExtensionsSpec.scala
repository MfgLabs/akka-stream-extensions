package com.mfglabs.stream
package extensions.elasticsearch

import akka.actor.ActorSystem
import akka.stream._
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.node.NodeBuilder
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Minutes, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpec}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

import org.elasticsearch.common.settings.Settings
import org.elasticsearch.node.NodeBuilder.nodeBuilder

class ElasticExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  implicit val as = ActorSystem()
  implicit val fm = ActorMaterializer()
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(1, Minutes), interval = Span(100, Millis))
  implicit val blockingEc = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  private val DEFAULT_DATA_DIRECTORY = "target/elasticsearch-data"

  "EsStream" should "execute a query a get the result as a stream" in {
    val node = nodeBuilder()
      .settings(Settings.settingsBuilder()
          .put("http.enabled", false)
          .put("path.data", DEFAULT_DATA_DIRECTORY)
          .put("path.home", "/")
       )
      .local(true)
      .node()
    implicit val client = node.client()

    val index = "test"
    val `type` = "type"

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

    client.close()
    node.close()
  }

}