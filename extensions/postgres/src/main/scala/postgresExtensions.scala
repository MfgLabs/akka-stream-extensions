package com.mfglabs.stream
package extensions.postgres

import java.io._

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.mfglabs.stream.extensions.postgres.PostgresVersion.{Eight, Nine}
import org.postgresql.PGConnection
import scala.concurrent._
import java.sql.Connection

import scala.util.{Failure, Try, Success}

abstract class PostgresVersion(val value:String)
object PostgresVersion {
  case object Nine extends PostgresVersion("9.6")
  case object Eight extends PostgresVersion("8.4")

  private val seq = Seq(Nine, Eight)

  def apply(v:String) =
    seq.find(_.value == v)
      .getOrElse(throw new IllegalArgumentException(s"Unsupported postgres version ($v), must be one of ${seq.map(_.value).mkString(", ")}"))
}

trait PgStream {
  /**
   * Execute a SQL query and get its result as a stream.
   * @param sqlQuery sql query
   * @param options options of the Postgres COPY command. For example, Map("DELIMITER" -> "','")
   * @param outputStreamTransformer optional output stream transformer
   * @param pgVersion PostgreSQL version (8.x or 9.x)
   * @param conn Postgres connection
   * @param ec ec that will be used for Postgres' blocking operations
   * @return
   */
  def getQueryResultAsStream(
    sqlQuery: String,
    options: Map[String, String],
    pgVersion : PostgresVersion = PostgresVersion.Nine,
    outputStreamTransformer : OutputStream => OutputStream = identity
  )(implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Source[ByteString, Future[IOResult]] = {
    val copyManager = conn.getCopyAPI()
    val os = new PipedOutputStream()
    val is = new PipedInputStream(os)
    val tos = outputStreamTransformer(os)
    val p = Promise[ByteString]
    val errorStream = Source.fromFuture(p.future) // hack to fail the stream if error in copyOut
    val optsStr = optionsToStr(pgVersion, options)
    val copyOutQuery = s"COPY ($sqlQuery) TO STDOUT $optsStr"
    Future {
      Try(copyManager.copyOut(copyOutQuery, tos)) match {
        case Success(_) =>
          p.success(ByteString.empty)
          tos.close()
          os.close()
        case Failure(err) =>
          p.failure(err)
          tos.close()
          os.close()
      }
    }(ec.value)
    StreamConverters.fromInputStream(() => is).concat(errorStream)
  }

  /**
   * Insert a stream in a Postgres table (one upstream chunk will correspond to one line).
   * Insertion order is not guaranteed with chunkInsertionConcurrency > 1.
   *
   * @return a stream of number of inserted lines by chunk
   * @param schema
   * @param table can be table_name or table_name(column1, column2) to insert data in specific columns
   * @param nbLinesPerInsertionBatch
   * @param chunkInsertionConcurrency
   * @param pgVersion PostgreSQL version (8.x or 9.x)
   * @param conn
   * @param ec
   * @return
   */
  def insertStreamToTable(schema: String, table: String, options: Map[String, String], pgVersion : PostgresVersion = PostgresVersion.Nine, nbLinesPerInsertionBatch: Int = 20000,
                          chunkInsertionConcurrency: Int = 1)
                         (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Flow[ByteString, Long, NotUsed] = {
    val optsStr = optionsToStr(pgVersion, options)
    val copyQuery = s"COPY ${schema}.${table} FROM STDIN $optsStr"
    val copyManager = conn.getCopyAPI()
    Flow[ByteString]
      .map(_.utf8String)
      .grouped(nbLinesPerInsertionBatch)
      .mapAsyncUnordered(chunkInsertionConcurrency) { chunk =>
      Future {
        copyManager.copyIn(copyQuery, new StringReader(chunk.mkString("\n")))
      }(ec.value)
    }
  }

  private def optionsToStr(pgVersion : PostgresVersion, options: Map[String, String]) = {
    pgVersion match {
      case Nine =>
        if (options.isEmpty) ""
        else
          " (" + options
            .map { case (k, v) => s"$k $v" }
            .mkString(",") + ")"
      case Eight =>
        def optToStr(st: Map[String, String]) = st.map { case (k, v) => s"$k $v" }.mkString(" ")
        val csvOptionKeys = Set("header", "quote", "escape", "force quote")
        val (csvOptions, commonOptions) = options.partition { case (k, _) => csvOptionKeys.contains(k.toLowerCase()) }
        val csvOptsStr = if (csvOptions.isEmpty) "" else " CSV " + optToStr(csvOptions)
        s"${optToStr(commonOptions)} $csvOptsStr"
    }
  }

  def sqlConnAsPgConnUnsafe(conn: Connection) = conn.asInstanceOf[PGConnection]
}

object PgStream extends PgStream
