package com.mfglabs.stream
package extensions.elasticsearch

import scala.Stream
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future

import org.elasticsearch.action.search.{SearchResponse, SearchRequestBuilder}
import org.elasticsearch.client.{ Client => EsClient }
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.search.SearchHit

import com.mfglabs.stream.ExecutionContextForBlockingOps

import akka.NotUsed
import akka.stream.scaladsl.Source

trait EsStream {
  import EsHelper._

  /**
   * Get the result of a search query as a stream.
   * Internally, the scan/scroll ES API is used to improve performance when fetching large chunks of data.
   * @param query search query
   * @param index ES index
   * @param types ES type
   * @param scrollKeepAlive ES scroll keep alive
   * @param scrollSize ES scroll size
   */
  def queryAsStream(
    query           : QueryBuilder,
    index           : String,
    `type`          : String,
    scrollKeepAlive : FiniteDuration,
    scrollSize      : Int,
    build           : SearchRequestBuilder => SearchRequestBuilder = identity
  )(implicit es: EsClient, ec: ExecutionContextForBlockingOps): Source[String, NotUsed] = {
    searchStream(index, scrollKeepAlive, scrollSize){ srb =>
      srb.setQuery(query)
         .setTypes(`type`)
    }.mapConcat { h => Option.apply(h.getSourceAsString).to[Seq] }
  }

  def hits(sr: SearchResponse): Seq[SearchHit] = sr.getHits.getHits.to[Seq]

  /**
   * Get the result of a search query as a stream.
   * Internally, the scan/scroll ES API is used to improve performance when fetching large chunks of data.
   * @param query search query
   * @param index ES index
   * @param scrollKeepAlive ES scroll keep alive
   * @param scrollSize ES scroll size
   * @param build : Add additionnal Search parameter
   * @return
   */
  protected def searchStream(
    index           : String,
    scrollKeepAlive : FiniteDuration,
    scrollSize      : Int
  )(
    build           : SearchRequestBuilder => SearchRequestBuilder
  )(implicit es: EsClient, ec: ExecutionContextForBlockingOps): Source[SearchHit, NotUsed] = {
    val builder = build(
      es.prepareSearch(index)
        .setScroll(new TimeValue(scrollKeepAlive.toMillis))
        .setSize(scrollSize)
    )

    Source.unfoldAsync[Either[SearchRequestBuilder, SearchResponse], Seq[SearchHit]](Left(builder)){
      case Left(srb) =>
        srb.execute().asScala.map { sr =>
          Some(Right(sr) -> hits(sr))
        }(ec.value)

      case Right(psr) if psr.getHits.getHits.nonEmpty =>
        val next = es
          .prepareSearchScroll(psr.getScrollId())
          .setScroll(new TimeValue(scrollKeepAlive.toMillis))

        next.execute().asScala.map { sr =>
          Some(Right(sr) -> hits(sr))
        }(ec.value)

      case Right(_) => Future.successful(None)
    }.mapConcat { identity }
  }

}

object EsStream extends EsStream
