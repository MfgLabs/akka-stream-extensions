package commons

import org.scalatest.{BeforeAndAfterEach, Suite}
import tugboat.{ContainerConfig, HostConfig}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random


trait DockerContainer extends BeforeAndAfterEach {
  self: Suite =>

  def timeout = 30.seconds

  def containerName = Random.alphanumeric.take(8).mkString("")

  def containerConfig: ContainerConfig

  def hostConfig: HostConfig

  val docker = tugboat.Docker()

  var container: Option[String] = None

  def dockerIp = {
    println("//////////////////////////////////////////////")
    println(docker.hostStr)
    println(docker.host)
    println((docker.host / "TOTO").toRequestBuilder.build().getURI)
    if (docker.hostStr.startsWith("https")){
      val reg = """http[s]*://(.*):.*""".r
      val reg(ip) = docker.hostStr
      println("IP : "+ ip)
      ip
    } else {
      //"localhost" //tcp://127.0.0.1:4243"//
      "unix:///var/run/docker.sock"
    }
  }

  override protected def beforeEach(): Unit = {
    val f = for {
      c <- docker.containers.Create(containerConfig, Some(containerName))()
      _ = println("//////////////////////////////////////////////")
      _ = println(docker.hostStr)
      _ = println(docker.host)
      _ = println(docker.host / "TOTO")
      _ <- docker.containers.get(c.id).Start(hostConfig)()
    } yield c.id
    container = Some(Await.result(f, timeout))

    println(s"Container ${container.get} started")
  }

  override protected def afterEach(): Unit = {
    container.foreach { id =>
      println(s"Stopping container $id...")
      Await.result(docker.containers.get(id).stop()(), timeout)
      println(s"Container $id stopped")
    }
  }
}