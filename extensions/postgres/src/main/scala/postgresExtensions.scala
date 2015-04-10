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
   * Get a postgres table as a stream source (each line is separated with '\n')
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
   * Inserts a stream as a postgres table (one chunk will correspond to one line).
   * Insertion order is guaranteed even with chunkInsertionConcurrency > 1
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
    val optionsStr = options
      .map { case (k, v) => s"$k $v" }
      .mkString(",")

    val copyManager = conn.getCopyAPI()
    Flow[ByteString]
      .map(_.utf8String)
      .grouped(nbLinesPerInsertionBatch)
      .via(FlowExt.mapAsyncWithBoundedConcurrency(chunkInsertionConcurrency) { chunk =>
        val query = s"COPY ${schema}.${table} FROM STDIN ($optionsStr)"
        Future {
          copyManager.copyIn(query, new StringReader(chunk.mkString("\n")))
        }(ec.value)
      })
  }

  def sqlConnAsPgConnUnsafe(conn: Connection) = conn.asInstanceOf[PGConnection]
}

object PgStream extends PgStream
