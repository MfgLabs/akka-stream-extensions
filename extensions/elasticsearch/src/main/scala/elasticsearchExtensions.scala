package com.mfglabs.stream
package extensions.elasticsearch

import akka.NotUsed
import akka.stream.scaladsl._
import com.mfglabs.stream.{ExecutionContextForBlockingOps, SourceExt}
import org.elasticsearch.action.search.SearchType
import org.elasticsearch.client.{Client => EsClient}
import org.elasticsearch.index.query.QueryBuilder

import scala.concurrent._
import scala.concurrent.duration._

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
    implicit val ecValue = ec.value

    lazy val futInitSearchResp = Future {
      es
        .prepareSearch(index)
        .setTypes(`type`)
        .setSearchType(SearchType.SCAN)
        .setScroll(scrollKeepAlive.toMillis.toString)
        .setSize(scrollSize)
        .setQuery(query)
        .get()
    }

    SourceExt.seededLazyAsync(futInitSearchResp) { initSearchResp =>
      SourceExt.unfoldPullerAsync(initSearchResp) { searchResp =>
        Future {
          es
            .prepareSearchScroll(searchResp.getScrollId)
            .setScroll(scrollKeepAlive.toMillis.toString)
            .get
        }
        .map { nextSearchResp =>
          val docs = nextSearchResp.getHits.getHits.toVector.map(_.getSourceAsString)

          if (docs.isEmpty) None -> None
          else Some(docs) -> Some(nextSearchResp)
        }
      }
    }
    .mapConcat(identity)
  }

}

object EsStream extends EsStream