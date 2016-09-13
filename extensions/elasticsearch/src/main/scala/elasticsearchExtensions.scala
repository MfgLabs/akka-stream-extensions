package com.mfglabs.stream
package extensions.elasticsearch

import scala.Stream
import scala.collection.immutable.Stream.consWrapper
import scala.concurrent.duration.FiniteDuration

import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.{ Client => EsClient }
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.QueryBuilder

import com.mfglabs.stream.ExecutionContextForBlockingOps

import akka.NotUsed
import akka.stream.scaladsl.Source

trait EsStream {

  /**
   * Get the result of a search query as a stream.
   * Internally, the scan/scroll ES API is used to improve performance when fetching large chunks of data.
   * @param query search query
   * @param index ES index
   * @param `type` ES type
   * @param scrollKeepAlive ES scroll keep alive
   * @param scrollSize ES scroll size
   * @param es
   * @param ec
   * @return
   */
  def queryAsStream(query: QueryBuilder, index: String, `type`: String, scrollKeepAlive: FiniteDuration, scrollSize: Int)
                   (implicit es: EsClient, ec: ExecutionContextForBlockingOps): Source[String, NotUsed] = {
    queryAsStream(query, index, `type`, scrollKeepAlive, scrollSize, Array.empty, Array.empty, List())
  }
  /**
   * Get the result of a search query as a stream.
   * Internally, the scan/scroll ES API is used to improve performance when fetching large chunks of data.
   * @param query search query
   * @param index ES index
   * @param `type` ES type
   * @param scrollKeepAlive ES scroll keep alive
   * @param scrollSize ES scroll size
   * @param included
   * @param excluded
   * @param addField
   * @param es
   * @param ec
   * @return
   */
  def queryAsStream(query: QueryBuilder,
                    index: String,
                    `type`: String,
                    scrollKeepAlive: FiniteDuration,
                    scrollSize: Int,
                    included: Array[String],
                    excluded: Array[String],
                    addField: List[String])
                   (implicit es: EsClient, ec: ExecutionContextForBlockingOps): Source[String, NotUsed] = {
    implicit val ecValue = ec.value

    def req(query: QueryBuilder) = {
      val srb = es
        .prepareSearch(index)
        .setTypes(`type`)
        .setQuery(query)                 // Query
        .setScroll(new TimeValue(scrollKeepAlive.toMillis))
        .setSize(scrollSize)

      addField.foreach { x => srb.addField(x) }
      if (!included.isEmpty || !excluded.isEmpty) srb.setFetchSource(included, excluded)

      val res = srb
        .execute()
        .actionGet()

      val docs = res.getHits.getHits.toVector.map(_.getSourceAsString)

      docs #:: next(res)
    }
 
    def next(res: SearchResponse): Stream[Vector[String]] = {
      val newRes = es
        .prepareSearchScroll(res.getScrollId())
        .setScroll(new TimeValue(scrollKeepAlive.toMillis))
        .execute()
        .actionGet

      val docs = newRes.getHits.getHits.toVector.map(_.getSourceAsString)

      if (docs.isEmpty) Stream.Empty
      else docs #:: next(newRes)
    }

    Source(req(query)).mapConcat { identity }
  }

}

object EsStream extends EsStream