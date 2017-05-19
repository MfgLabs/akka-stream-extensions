package com.mfglabs.stream
package extensions.postgres

import java.sql.{DriverManager, Connection}
import org.postgresql.util.PSQLException
import org.scalatest.{Suite, BeforeAndAfter}
import scala.sys.process._
import scala.util.{Failure, Success, Try}
import com.typesafe.config.ConfigFactory
/**
 * for each test, creates a PostgresSQL docker container and provides a connection to its database
 */
trait DockerTmpDB extends BeforeAndAfter { self: Suite =>

  import Debug._

  val version: PostgresVersion = PostgresVersion(ConfigFactory.load().getString("postgres.version"))

  Class.forName("org.postgresql.Driver")
  implicit var conn : Connection = _

  val dockerInstances = collection.mutable.Buffer.empty[String]

  def newPGDB(): Int = {
    val port: Int = 5432 + (math.random * (10000 - 5432)).toInt
    Try {
      s"docker pull postgres:${version.value}".pp.!!.trim
      val containerId =
        s"""docker run -p $port:5432 -e POSTGRES_PASSWORD=pwd -d postgres:${version.value}""".pp.!!.trim
      dockerInstances += containerId.pp("New docker instance with id")
      port
    } match {
      case Success(p) => p
      case Failure(err) =>
        throw  new IllegalStateException(s"Error while trying to run docker container", err)
    }
  }

  lazy val dockerIp: String =
    Try("docker-machine ip default".!!.trim).toOption
      .orElse {
        val conf = ConfigFactory.load()
        if (conf.hasPath("docker.ip")) Some(conf.getString("docker.ip")) else None
      }
      .getOrElse("127.0.0.1") // platform dependent

  //ugly solution to wait for the connection to be ready
  def waitsForConnection(port : Int) : Connection = {
    try {
      DriverManager.getConnection(s"jdbc:postgresql://$dockerIp:$port/postgres", "postgres", "pwd")
    } catch {
      case err: PSQLException =>
        println("Retrying DB connection...")
        Thread.sleep(1000)
        waitsForConnection(port)
    }
  }

  before {
    val port = newPGDB()
    println(s"New postgres ${version.value} instance at port $port")
    Thread.sleep(5000)
    conn = waitsForConnection(port)
  }

  after {
    conn.close()
    dockerInstances.toSeq.foreach { dockerId =>
      s"docker stop $dockerId".pp.!!
      s"docker rm $dockerId".pp.!!
    }
  }

}

object Debug {

  implicit class RichString(s:String){
    def pp :String = pp(None)
    def pp(p:String) :String = pp(Some(p))

    private def pp(p:Option[String]) = {
      println(p.map(_ + " ").getOrElse("") + s)
      s
    }
  }
}


