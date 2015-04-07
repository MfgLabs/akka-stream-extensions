package com.mfglabs.stream
package extensions.postgres

import java.io.File
import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.scalatest.time._
import org.scalatest._
import concurrent.ScalaFutures
import scala.concurrent.Future
import scala.util.Try

/**
 * To run this test, launch a local postgresql instance and put the right connection info into DriverManager.getConnection
 */

class PostgresExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll with DockerTmpDB {

  import extensions.postgres._

  val bucket = "mfg-commons-aws"

  val keyPrefix = "test/extensions/postgres"

  implicit val as = ActorSystem()
  implicit val fm = ActorFlowMaterializer()
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Minutes), interval = Span(5, Millis))

  Class.forName("org.postgresql.Driver")

  "PgStream" should "stream a file to a postgres table and stream a query from a postgre table" in {
    val stmt = conn.createStatement()
    implicit val pgConn = PgStream.sqlConnAsPgConnUnsafe(conn)
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val blockingEc = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

    stmt.execute(
      s"""
         create table public.test_postgres_aws_s3(
          io_id integer,
          dsp_name text,
          advertiser_id integer,
          campaign_id integer,
          strategy_id integer,
          day date,
          impressions integer,
          clicks integer,
          post_view_conversions float8,
          post_click_conversions float8,
          media_cost float8,
          total_ad_cost float8,
          total_cost float8
         )
       """
    )

    val insertTable = "test_postgres_aws_s3(io_id, dsp_name, advertiser_id, campaign_id, strategy_id, day, impressions, " +
      "clicks, post_view_conversions, post_click_conversions, media_cost, total_ad_cost, total_cost)"

    val nbLinesInserted = new AtomicLong(0L)

    val futLines = SourceExt
      .fromFile(new File(getClass.getResource("/report.csv0000_part_00").getPath), maxChunkSize = 5 * 1024 * 1024)
      .via(FlowExt.rechunkByteStringBySeparator())
      .via(PgStream.insertStreamToTable("public", insertTable, chunkInsertionConcurrency = 2))
      .via(FlowExt.fold(0L)(_ + _))
      .map { total =>
        nbLinesInserted.set(total)
        PgStream.getQueryResultAsStream("select * from public.test_postgres_aws_s3")
      }
      .flatten(FlattenStrategy.concat)
      .via(FlowExt.rechunkByteStringBySize(5 * 1024 * 1024))
      .via(FlowExt.rechunkByteStringBySeparator())
      .map(_.utf8String)
      .runWith(SinkExt.collect)

    val futExpectedLines = SourceExt
      .fromFile(new File(getClass.getResource("/report.csv0000_part_00").getPath), maxChunkSize = 5 * 1024 * 1024)
      .via(FlowExt.rechunkByteStringBySeparator())
      .map(_.utf8String)
      .runWith(SinkExt.collect)

    whenReady(futLines zip futExpectedLines) { case (lines, expectedLines) =>
      lines.length shouldEqual expectedLines.length
      lines.length shouldEqual nbLinesInserted.get

      lines.sorted.zip(expectedLines.sorted).foreach { case (line, expectedLine) =>
        line.split(",").map { s =>
          Try(s.toDouble).map(BigDecimal(_).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble).getOrElse(s)
        } shouldEqual expectedLine.split(",").map { s =>
          Try(s.toDouble).map(BigDecimal(_).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble).getOrElse(s)
        }
      }

      stmt.close()
    }


  }
}


