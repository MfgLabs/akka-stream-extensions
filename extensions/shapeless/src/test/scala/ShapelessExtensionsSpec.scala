package com.mfglabs.stream
package extensions.shapeless

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import org.scalatest.time._
import org.scalatest._
import concurrent.ScalaFutures

import shapeless._


/**
 * To run this test, launch a local postgresql instance and put the right connection info into DriverManager.getConnection
 */
class ShapelessExtensionsSpec extends FlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  import extensions.shapeless._

  implicit val as = ActorSystem()
  implicit val fm = ActorMaterializer()
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Minutes), interval = Span(5, Millis))

  "ShapelessStream" should "streamAny" in {

    type C = Int :+: String :+: Boolean :+: CNil

    // WARNING Don't forget to change the access_token with a valid one
    val sink = Sink.fold[Seq[Any], Any](Seq())(_ :+ _)

    val f = GraphDSL.create(sink) { implicit builder => sink =>
      import GraphDSL.Implicits._
      val s = Source.fromIterator(() => Seq(
        Coproduct[C](1),
        Coproduct[C]("foo"),
        Coproduct[C](2),
        Coproduct[C](false),
        Coproduct[C]("bar"),
        Coproduct[C](3),
        Coproduct[C](true)
      ).toIterator)


      val flowInt = Flow[Int].map{i => println("i:"+i); i}
      val flowString = Flow[String].map{s => println("s:"+s); s}
      val flowBool = Flow[Boolean].map{s => println("s:"+s); s}

      val fr = builder.add(ShapelessStream.coproductFlowAny(flowInt :: flowString :: flowBool :: HNil))

      s ~> fr.in
           fr.out ~> sink

      ClosedShape
    }

    RunnableGraph.fromGraph(f).run().futureValue.toSet should equal (Set[Any](
      1,
      "foo",
      2,
      false,
      "bar",
      3,
      true
    ))
  }

  it should "stream" in {

    type C = Int :+: String :+: Boolean :+: CNil
    // WARNING Don't forget to change the access_token with a valid one
    val sink = Sink.fold[Seq[C], C](Seq())(_ :+ _)

    val f = GraphDSL.create(sink) { implicit builder => sink =>
      import GraphDSL.Implicits._
      val s = Source.fromIterator(() => Seq(
        Coproduct[C](1),
        Coproduct[C]("foo"),
        Coproduct[C](2),
        Coproduct[C](false),
        Coproduct[C]("bar"),
        Coproduct[C](3),
        Coproduct[C](true)
      ).toIterator)

      val flowInt = Flow[Int].map{i => println("i:"+i); i}
      val flowString = Flow[String].map{s => println("s:"+s); s}
      val flowBool = Flow[Boolean].map{s => println("s:"+s); s}

      val fr = builder.add(ShapelessStream.coproductFlow(flowInt :: flowString :: flowBool :: HNil))

      s ~> fr.in
           fr.out ~> sink

      ClosedShape
    }

    RunnableGraph.fromGraph(f).run().futureValue.toSet should equal (Set(
      Coproduct[C](1),
      Coproduct[C]("foo"),
      Coproduct[C](2),
      Coproduct[C](false),
      Coproduct[C]("bar"),
      Coproduct[C](3),
      Coproduct[C](true)
    ))
  }
}



