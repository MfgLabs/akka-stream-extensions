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
   * @param v8Compatible whether the underlying postgreSQL database version is 8.x (else it is 9.x)
   * @param conn Postgres connection
   * @param ec ec that will be used for Postgres' blocking operations
   * @return
   */
  def getQueryResultAsStream(sqlQuery: String, options: Map[String, String], v8Compatible : Boolean = false, outputStreamTransformer : OutputStream => OutputStream = identity)
                      (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Source[ByteString, (akka.actor.ActorRef, Unit)] = {
    val copyManager = conn.getCopyAPI()
    val os = new PipedOutputStream()
    val is = new PipedInputStream(os)
    val tos = outputStreamTransformer(os)
    val p = Promise[ByteString]
    val errorStream = Source(p.future) // hack to fail the stream if error in copyOut
    val optsStr = if (v8Compatible) optionsToStrV8(options) else optionsToStrV9(options)
    val copyOutQuery = s"COPY ($sqlQuery) TO STDOUT $optsStr"
    println(copyOutQuery)
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
   * @param v8Compatible whether the underlying postgreSQL database version is 8.x (else it is 9.x)
   * @param conn
   * @param ec
   * @return
   */
  def insertStreamToTable(schema: String, table: String, options: Map[String, String], v8Compatible : Boolean = false, nbLinesPerInsertionBatch: Int = 20000,
                          chunkInsertionConcurrency: Int = 1)
                         (implicit conn: PGConnection, ec: ExecutionContextForBlockingOps): Flow[ByteString, Long, Unit] = {
    val optsStr = if (v8Compatible) optionsToStrV8(options) else optionsToStrV9(options)
    val copyQuery = s"COPY ${schema}.${table} FROM STDIN $optsStr"
    val copyManager = conn.getCopyAPI()
    println(copyQuery)
    Flow[ByteString]
      .map(_.utf8String)
      .grouped(nbLinesPerInsertionBatch)
      .mapAsyncUnordered(chunkInsertionConcurrency) { chunk =>
        Future {
          copyManager.copyIn(copyQuery, new StringReader(chunk.mkString("\n")))
        }(ec.value)
      }
  }

  private def optionsToStrV8(options: Map[String, String]) = {
    def optToStr(st : Map[String,String]) = st.map { case (k, v) => s"$k $v"}.mkString(" ")
    val csvOptionKeys = Set("header", "quote", "escape", "force quote")
    val (csvOptions, commonOptions) = options.partition{ case (k,v) => csvOptionKeys.contains(k.toLowerCase())}
    val csvOptsStr = if (csvOptions.isEmpty) "" else " CSV " + optToStr(csvOptions)
    s"${optToStr(commonOptions)} $csvOptsStr"
  }

  private def optionsToStrV9(options : Map[String, String]) = {
      if (options.isEmpty) ""
      else
        " (" + options
          .map { case (k, v) => s"$k $v" }
          .mkString(",") + ")"
  }

  def sqlConnAsPgConnUnsafe(conn: Connection) = conn.asInstanceOf[PGConnection]
}

object PgStream extends PgStream
