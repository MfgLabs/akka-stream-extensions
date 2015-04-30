package com.mfglabs.stream
package extensions.postgres

import java.io._

import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.postgresql.PGConnection
import scala.concurrent._
import java.sql.Connection

import scala.util.{Failure, Try, Success}

trait PgStream {
  /**
   * Execute a SQL query and get its result as a stream.
   * @param sqlQuery sql query
   * @param options options of the Postgres COPY command. For example, Map("FORMAT" -> "CSV", "DELIMITER" -> "','")
   * @param outputStreamTransformer optional output stream transformer
   * @param conn Postgres connection
   * @param ec ec that will be used for Postgres' blocking operations
   * @return
   */
  def getQueryResultAsStream(sqlQuery: String, options: Map[String, String], outputStreamTransformer : OutputStream => OutputStream = identity)
                      (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Source[ByteString, (akka.actor.ActorRef, Unit)] = {
    val copyManager = conn.getCopyAPI()
    val os = new PipedOutputStream()
    val is = new PipedInputStream(os)
    val tos = outputStreamTransformer(os)

    val p = Promise[ByteString]
    val errorStream = Source(p.future) // hack to fail the stream if error in copyOut

    val optionsStr = options
      .map { case (k, v) => s"$k $v" }
      .mkString(",")

    Future {
      Try(copyManager.copyOut(s"COPY ($sqlQuery) TO STDOUT ($optionsStr)", tos)) match {
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

    Source.concat(SourceExt.fromStream(is), errorStream)
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
   * @param conn
   * @param ec
   * @return
   */
  def insertStreamToTable(schema: String, table: String, options: Map[String, String], nbLinesPerInsertionBatch: Int = 20000,
                          chunkInsertionConcurrency: Int = 1)
                         (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Flow[ByteString, Long, Unit] = {
    val optionsStr =
      if (options.isEmpty) ""
        else
        " (" + options
          .map { case (k, v) => s"$k $v" }
          .mkString(",") + ")"
    val query = s"COPY ${schema}.${table} FROM STDIN $optionsStr"
    insertStreamToTable(query,nbLinesPerInsertionBatch,chunkInsertionConcurrency)(conn,ec)
  }

  /**
   * equivalent of insertStreamToTable, but for PostgreSQL 8.x
   */
  def insertStreamToTableV8(schema: String, table: String, options: Map[String, String], nbLinesPerInsertionBatch: Int = 20000,
                          chunkInsertionConcurrency: Int = 1)
                         (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Flow[ByteString, Long, Unit] = {
    def optToStr(st : Map[String,String]) = st.map { case (k, v) => (k,s"$k $v")}.mkString(" ")
    val csvOptionKeys = Set("header", "quote", "escape", "force quote")
    val (csvOptions, commonOptions) = options.partition{ case (k,v) => csvOptionKeys.contains(k.toLowerCase())}
    val csvOptsStr = if (csvOptions.isEmpty) "" else " CSV " + optToStr(csvOptions)
    val query = s"COPY ${schema}.${table} FROM STDIN ${optToStr(commonOptions)} $csvOptsStr"
    insertStreamToTable(query,nbLinesPerInsertionBatch,chunkInsertionConcurrency)(conn,ec)
  }

  private def insertStreamToTable(copyQuery : String, nbLinesPerInsertionBatch: Int, chunkInsertionConcurrency: Int)
                                 (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Flow[ByteString, Long, Unit] = {
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

  def sqlConnAsPgConnUnsafe(conn: Connection) = conn.asInstanceOf[PGConnection]
}

object PgStream extends PgStream
