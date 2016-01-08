package com.mfglabs.stream
package extensions.postgres

import java.io.File
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.util.ByteString
import commons.{DockerTempPostgres, DockerTempPostgresV8, DockerTempPostgresV9}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._
import akka.stream._
import scala.util.Try

trait PostgresExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  self: DockerTempPostgres =>

  implicit val as = ActorSystem()
  implicit val fm = ActorMaterializer()
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Minutes), interval = Span(5, Millis))


  "PgStream" should "stream a file to a Postgres table and stream a sql query from a Postgres table" in {
    val stmt = conn.createStatement()
    implicit val pgConn = PgStream.sqlConnAsPgConnUnsafe(conn)
    implicit val blockingEc = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

    stmt.execute(
      s"""
       create table public.test_postgres(
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

    val insertTable = "test_postgres(io_id, dsp_name, advertiser_id, campaign_id, strategy_id, day, impressions, " +
      "clicks, post_view_conversions, post_click_conversions, media_cost, total_ad_cost, total_cost)"

    val nbLinesInserted = new AtomicLong(0L)

    val futLines = SourceExt
      .fromFile(new File(getClass.getResource("/report.csv0000_part_00").getPath), maxChunkSize = 5 * 1024 * 1024)
      .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 1 * 1024 * 1024))
      .via(PgStream.insertStreamToTable("public", insertTable, Map("DELIMITER" -> "','"), pgVersion = self.version, chunkInsertionConcurrency = 2))
      .via(FlowExt.fold(0L)(_ + _))
      .map { total =>
      nbLinesInserted.set(total)
      PgStream.getQueryResultAsStream("select * from public.test_postgres", Map("DELIMITER" -> "','"),pgVersion = self.version)
    }
      .flatMapConcat(identity)
      .via(FlowExt.rechunkByteStringBySize(5 * 1024 * 1024))
      .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 1 * 1024 * 1024))
      .map(_.utf8String)
      .runWith(SinkExt.collect)

    val futExpectedLines = SourceExt
      .fromFile(new File(getClass.getResource("/report.csv0000_part_00").getPath), maxChunkSize = 5 * 1024 * 1024)
      .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 1 * 1024 * 1024))
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

class PostgresExtensionsForV9Spec extends PostgresExtensionsSpec with DockerTempPostgresV9
class PostgresExtensionsForV8Spec extends PostgresExtensionsSpec with DockerTempPostgresV8


