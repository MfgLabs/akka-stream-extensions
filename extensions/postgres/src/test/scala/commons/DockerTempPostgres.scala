package commons

import java.sql.{DriverManager, Connection}
import java.util.{Timer, TimerTask}

import org.scalatest.Suite
import tugboat._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

trait DockerTempPostgres extends DockerContainer {
  self: Suite =>

  override val docker = tugboat.Docker()
  Class.forName("org.postgresql.Driver")
  implicit var conn : Connection = _

  def dbPort =  sys.props.getOrElse("postgres_port", "5432").toInt
  def pgUser = "postgres"
  def pgPassword = "postgres"

  var containerStarted = false

  val containerConfig = ContainerConfig(
    image = "postgres:9.3",
    env = Map("POSTGRES_USER" -> pgUser, "POSTGRES_PASSWORD" -> pgPassword)
  //, hostname = "http://localhost:4243"//tcp://127.0.0.1:4243"
  )

  val hostConfig = HostConfig(
    ports = Map(Port.Tcp(5432) -> List(PortBinding.local(dbPort)))
  )

  val dbUrl = s"postgres://$pgUser:$pgPassword@$dockerIp:$dbPort/postgres"
  val jdbcDbUrl = s"jdbc:postgresql://$dockerIp:$dbPort/postgres"

  def delay[T](timeout: Duration)(block: => T)(implicit executor: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    val t = new Timer
    t.schedule(new TimerTask {
      override def run(): Unit = {
        promise.complete(Try(block))
      }
    }, timeout.toMillis)
    promise.future
  }

  def waitForDBStarted(): Future[Unit] = {
    print(".")
    delay(500.milliseconds)({}).flatMap { _ =>
      if(!containerStarted)
        waitForDBStarted()
      else {
        Future.successful(println("OK"))
      }
    }
  }

  def createConnection(): Connection =
    DriverManager.getConnection(jdbcDbUrl, pgUser, pgPassword)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    container.foreach { id =>
      val (stopper, completeFuture) = docker.containers.get(id)
        .logs
        .follow(true)
        .stdout(true)
        .stderr(true)
        .stream { l =>
          if(!containerStarted)
            containerStarted = l.contains("database system is ready to accept connections")
        }

      println("Waiting for db")

      Await.result(waitForDBStarted(), 10.seconds)
      conn = createConnection()
    }
  }

  override protected def afterEach(): Unit = {
    conn.close()
    super.afterEach()
  }
}


trait DockerTempPostgres8 extends DockerTempPostgres {
  self: Suite =>

  override val containerConfig = ContainerConfig(
    image = "postgres:8.4",
    env = Map("POSTGRES_USER" -> pgUser, "POSTGRES_PASSWORD" -> pgPassword)
  )
}