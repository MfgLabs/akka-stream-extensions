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

import shapeless._


/**
 * To run this test, launch a local postgresql instance and put the right connection info into DriverManager.getConnection
 */
class ShapelessExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  import extensions.shapeless._

  implicit val as = ActorSystem()
  implicit val fm = ActorFlowMaterializer()
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Minutes), interval = Span(5, Millis))

  "ShapelessStream" should "stream" in {

    // WARNING Don't forget to change the access_token with a valid one
    val sink = Sink.fold[Seq[Any], Any](Seq())(_ :+ _)

    val f = FlowGraph.closed(sink) { implicit builder => sink =>
      import FlowGraph.Implicits._
      val s = Source(() => Seq(
        Coproduct[Int :+: String :+: Boolean :+: CNil](1),
        Coproduct[Int :+: String :+: Boolean :+: CNil]("foo"),
        Coproduct[Int :+: String :+: Boolean :+: CNil](2),
        Coproduct[Int :+: String :+: Boolean :+: CNil](false),
        Coproduct[Int :+: String :+: Boolean :+: CNil]("bar"),
        Coproduct[Int :+: String :+: Boolean :+: CNil](3),
        Coproduct[Int :+: String :+: Boolean :+: CNil](true)
      ).toIterator)


      val flowInt = Flow[Int].map{i => println("i:"+i); i}
      val flowString = Flow[String].map{s => println("s:"+s); s}
      val flowBool = Flow[Boolean].map{s => println("s:"+s); s}
      
      val fr = builder.add(ShapelessStream.coproductFlow(flowInt :: flowString :: flowBool :: HNil))

      s ~> fr.inlet
           fr.outlet ~> sink
    }

    whenReady(f.run()) { l => println("FINISHED:"+l)}
  }

}


    // implicitly[FlowTypes.Aux[
    //   Flow[Int, Int, Unit] :: Flow[String, String, Unit] :: HNil,
    //   Int :+: String :+: CNil,
    //   Int :+: String :+: CNil]
    // ]

    // implicitly[
    //   BuildOutlet.Aux[Int :+: String :+: CNil, Outlet[Int] :: Outlet[String] :: HNil]
    // ]

    // implicitly[
    //   Sel.Aux[Int :+: String :+: CNil, Outlet[Int] :: Outlet[String] :: HNil]
    // ]

    // Outlet2Flow.head[
    //   Int, String :+: CNil, Int, String :+: CNil,
    //   Outlet[Int] :: Outlet[String] :: HNil,
    //   Flow[Int, Int, Unit] :: Flow[String, String, Unit] :: HNil,
    //   Nat._1
    // ]

    // implicitly[
    //   Outlet2Flow.Aux[
    //     Int :+: String :+: CNil,
    //     Int :+: String :+: CNil, 
    //     Outlet[Int] :: Outlet[String] :: HNil,
    //     Flow[Int, Int, Unit] :: Flow[String, String, Unit] :: HNil,
    //     Nat._2
    //   ]
    // ]
