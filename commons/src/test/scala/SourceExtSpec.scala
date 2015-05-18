package com.mfglabs.stream

import java.io.{FileInputStream, File}

import akka.actor.ActorSystem
import akka.stream.{ActorFlowMaterializerSettings, ActorFlowMaterializer}
import akka.stream.scaladsl._
import akka.util.ByteString
import org.scalatest._
import org.scalatest.concurrent
import concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Millis, Seconds, Span}

import scala.concurrent.Future
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, FlatSpec}
import scala.concurrent._
import scala.concurrent.duration._

class SourceExtSpec extends FlatSpec with Matchers with ScalaFutures {
  import SourceExt._
  
  implicit val system = ActorSystem()
  implicit val mat = ActorFlowMaterializer(ActorFlowMaterializerSettings(system).withInputBuffer(16, 16))
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(2, Minutes), interval = Span(20, Millis))

  implicit val ecBlocking = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  val elements = for (i <- 1 to 500) yield i

  "bulkAsyncPuller" should "end the stream when nothing to pull" in {
    whenReady(
      bulkPullerAsync(0L)((_, _) => Future.successful((Seq.empty[Int], true)))
        .runWith(SinkExt.collect)
    ) { res => assert(res.isEmpty)}
  }

  it should "pull elements" in {
    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        val toPush = elements.drop(counter.toInt).take(nbElemToPush)
        Future.successful(toPush, toPush.isEmpty)
      }
      .runWith(SinkExt.collect)
    ) { res => assert(res == elements) }
  }

  it should "buffer unwanted elements by downstream" in {
    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        val toPush = elements.drop(counter.toInt).take(nbElemToPush + 2)
        Future.successful(toPush, toPush.isEmpty)
      }
      .runWith(SinkExt.collect)
    ) { res => assert(res == elements) }

    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        val toPush = elements.drop(counter.toInt).take(nbElemToPush + 20)
        Future.successful(toPush, toPush.isEmpty)
      }
        .runWith(SinkExt.collect)
    ) { res => assert(res == elements) }
  }

  it should "tolerate empty seq without ending the stream" in {
    var i = 0
    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        if (i % 2 == 0) {
          i = 1
          val toPush = elements.drop(counter.toInt).take(nbElemToPush)
          Future.successful(elements.drop(counter.toInt).take(nbElemToPush), toPush.isEmpty)
        } else {
          i = 0
          Future.successful(Seq.empty, false)
        }
      }
      .runWith(SinkExt.collect)
    ) { res => assert(res == elements)}
  }

  "unfoldPullerAsync" should "perform a async unfold" in {
    val futR = SourceExt.unfoldPullerAsync[Int, Int](0) {
      case i if i < 100 => Future.successful(Option(i * 10), Option(i + 1))
      case i if i == 100 => Future.successful(Option(i * 10), None)
    }
    .runWith(SinkExt.collect)

    whenReady(futR) { result =>
      result shouldEqual (for (i <- 0 to 100) yield i * 10).toSeq
    }
  }

  it should "not end the stream when we push no element" in {
    val futR = SourceExt.unfoldPullerAsync[Int, Int](0) {
      case i if i < 100 && i % 2 == 0 => Future.successful(None, Option(i + 1))
      case i if i < 100 => Future.successful(Option(i * 10), Option(i + 1))
      case i if i == 100 => Future.successful(None, None)
    }
    .runWith(SinkExt.collect)

    whenReady(futR) { result =>
      result shouldEqual (for (i <- (0 to 100).filterNot(_ % 2 == 0)) yield i * 10).toSeq
    }
  }

  "fromStream" should "read from an input stream" in {
    val filePath = getClass.getResource("/big.txt").getPath
    val futFile = SourceExt
      .fromStream(new FileInputStream(filePath), maxChunkSize = 5 * 1024 * 1024)
      .map(_.utf8String)
      .runFold("")(_ + _)

    val expectedFile = scala.io.Source.fromFile(filePath).mkString

    whenReady(futFile) { file =>
      file shouldEqual expectedFile
    }
  }

  "fromFile" should "read a local file" in {
    val filePath = getClass.getResource("/big.txt").getPath
    val futFile = SourceExt
      .fromFile(new File(filePath), maxChunkSize = 5 * 1024 * 1024)
      .map(_.utf8String)
      .runFold("")(_ + _)

    val expectedFile = scala.io.Source.fromFile(filePath).mkString

    whenReady(futFile) { file =>
      file shouldEqual expectedFile
    }
  }

  "seededLazyAsync" should "create a stream from a future lazily evaluated" in {
    def futTs() = Future.successful(System.nanoTime())

    val source = SourceExt.seededLazyAsync(futTs)(ts => Source.single(ts))
    val t2 = System.nanoTime()

    whenReady(source.runWith(SinkExt.collect)) {
      case t +: _ =>
        assert(t > t2)
        val t3 = System.nanoTime()
        whenReady(source.runWith(SinkExt.collect)) {
          case tt +: _ =>
            assert(tt > t3)
            assert(tt > t)
        }
    }

    // Testing failure with standard Source(fut)
    val source2 = Source(futTs)
    val t4 = System.nanoTime()
    whenReady(source2.runWith(SinkExt.collect)) {
      case t +: _ => assert(t < t4)
    }
  }

  "singleLazyAsync" should "create a stream from a future lazily evaluated" in {
    def futTs() = Future.successful(System.nanoTime())

    val source = SourceExt.singleLazyAsync(futTs)
    val t2 = System.nanoTime()

    whenReady(source.runWith(SinkExt.collect)) {
      case t +: _ =>
        assert(t > t2)
        val t3 = System.nanoTime()
        whenReady(source.runWith(SinkExt.collect)) {
          case tt +: _ =>
            assert(tt > t3)
            assert(tt > t)
        }
    }

    // Testing failure with standard Source(fut)
    val source2 = Source(futTs)
    val t4 = System.nanoTime()
    whenReady(source2.runWith(SinkExt.collect)) {
      case t +: _ => assert(t < t4)
    }
  }

  "singleLazy" should "create a stream from a future lazily evaluated" in {

    val source = SourceExt.singleLazy(System.nanoTime())
    val t2 = System.nanoTime()

    whenReady(source.runWith(SinkExt.collect)) {
      case t +: _ =>
        assert(t > t2)
        val t3 = System.nanoTime()
        whenReady(source.runWith(SinkExt.collect)) {
          case tt +: _ =>
            assert(tt > t3)
            assert(tt > t)
        }
    }

    // Testing failure with standard Source(fut)
    val source2 = Source.single(System.nanoTime())
    val t4 = System.nanoTime()
    whenReady(source2.runWith(SinkExt.collect)) {
      case t +: _ => assert(t < t4)
    }
  }

  "constantLazyAsync" should "create a repeated stream from a future lazily evaluated" in {

    val a = 314
    val source = SourceExt.constantLazyAsync(Future.successful(a + 10)).take(1000)
    val t2 = System.nanoTime()

    whenReady(source.runWith(SinkExt.collect)) { _ should equal (Vector.fill(1000)(a + 10)) }
  }

  "constantLazy" should "create a repeated stream from a future lazily evaluated" in {

    val a = 314
    val source = SourceExt.constantLazy(a + 10).take(1000)
    val t2 = System.nanoTime()

    whenReady(source.runWith(SinkExt.collect)) { _ should equal (Vector.fill(1000)(a + 10)) }
  }
}
