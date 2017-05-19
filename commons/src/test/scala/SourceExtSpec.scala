package com.mfglabs.stream

import java.io.File

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import org.scalatest._
import org.scalatest.concurrent
import concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Millis, Span}

import scala.concurrent.Future
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, FlatSpec}
import scala.concurrent._

class SourceExtSpec extends FlatSpec with Matchers with ScalaFutures {
  import SourceExt._

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(16, 16))
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(2, Minutes), interval = Span(20, Millis))

  implicit val ecBlocking = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  val elements = for (i <- 1 to 500) yield i

  "bulkAsyncPuller" should "end the stream when nothing to pull" in {
    whenReady(
      bulkPullerAsync(0L)((_, _) => Future.successful((Seq.empty[Int], true)))
        .runWith(Sink.seq)
    ) { res => assert(res.isEmpty)}
  }

  it should "pull elements" in {
    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        val toPush = elements.drop(counter.toInt).take(nbElemToPush)
        Future.successful(toPush -> toPush.isEmpty)
      }
      .runWith(Sink.seq)
    ) { res => assert(res == elements) }
  }

  it should "buffer unwanted elements by downstream" in {
    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        val toPush = elements.drop(counter.toInt).take(nbElemToPush + 2)
        Future.successful(toPush -> toPush.isEmpty)
      }
      .runWith(Sink.seq)
    ) { res => assert(res == elements) }

    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        val toPush = elements.drop(counter.toInt).take(nbElemToPush + 20)
        Future.successful(toPush -> toPush.isEmpty)
      }
        .runWith(Sink.seq)
    ) { res => assert(res == elements) }
  }

  it should "tolerate empty seq without ending the stream" in {
    var i = 0
    whenReady(
      bulkPullerAsync(0L) { (counter, nbElemToPush) =>
        if (i % 2 == 0) {
          i = 1
          val toPush = elements.drop(counter.toInt).take(nbElemToPush)
          Future.successful(elements.drop(counter.toInt).take(nbElemToPush) -> toPush.isEmpty)
        } else {
          i = 0
          Future.successful(Seq.empty -> false)
        }
      }
      .runWith(Sink.seq)
    ) { res => assert(res == elements)}
  }

  "unfoldPullerAsync" should "perform a async unfold" in {
    val futR = SourceExt.unfoldPullerAsync[Int, Int](0) {
      case i if i < 100 => Future.successful(Option(i * 10) -> Option(i + 1))
      case i if i == 100 => Future.successful(Option(i * 10) -> None)
    }
    .runWith(Sink.seq)

    whenReady(futR) { result =>
      result shouldEqual (for (i <- 0 to 100) yield i * 10).toSeq
    }
  }

  it should "not end the stream when we push no element" in {
    val futR = SourceExt.unfoldPullerAsync[Int, Int](0) {
      case i if i < 100 && i % 2 == 0 => Future.successful(None -> Option(i + 1))
      case i if i < 100 => Future.successful(Option(i * 10) -> Option(i + 1))
      case i if i == 100 => Future.successful(None -> None)
    }
    .runWith(Sink.seq)

    whenReady(futR) { result =>
      result shouldEqual (for (i <- (0 to 100).filterNot(_ % 2 == 0)) yield i * 10).toSeq
    }
  }

  "bulkAsyncPullerWithMaxRetries" should "manage max retry failure" in {
    @volatile var i = 0

    val b = bulkPullerAsyncWithMaxRetries(0L, 2) { (counter, nbElemToPush) =>
      i += 1
      if (i != 0 && i % 3 == 0) {
        Future.failed(new RuntimeException(""))
      } else {
        Future.successful(Seq(i) -> false)
      }
    }

    intercept[RuntimeException] {
      b.runWith(Sink.seq).futureValue
    }
    i should be (9)
  }

  "bulkPullerAsyncWithErrorMgt" should "manage manage failure" in {
    @volatile var i = 0

    val b = bulkPullerAsyncWithErrorMgt(0L)(
      (counter, nbElemToPush) => {
        i += 1
        if (i != 0 && i % 3 == 0) {
          Future.failed(new RuntimeException(""))
        } else {
          Future.successful(Seq(i) -> false)
        }
      },
      {
        case (_: RuntimeException, n) if n == 3 => true
        case _ => false
      }
    )

    intercept[RuntimeException] {
      b.runWith(Sink.seq).futureValue
    }
    i should be (9)
  }

  "bulkPullerAsyncWithErrorExpBackoff" should "manage failure with exp backoff" in {
    import scala.concurrent.duration._
    @volatile var i = 0

    val time0 = System.currentTimeMillis()
    val b = bulkPullerAsyncWithErrorExpBackoff(0L, 10.seconds, 1000.milliseconds) { (counter, nbElemToPush) =>
      i += 1
      Future.failed(new RuntimeException(""))
    }

    intercept[RuntimeException] {
      b.runWith(Sink.seq).futureValue
    }

    val time1 = System.currentTimeMillis()
    i should be (4)
    (time1 - time0) should be > 7000L
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

    whenReady(source.runWith(Sink.seq)) {
      case t +: _ =>
        assert(t > t2)
        val t3 = System.nanoTime()
        whenReady(source.runWith(Sink.seq)) {
          case tt +: _ =>
            assert(tt > t3)
            assert(tt > t)
        }
    }

    // Testing failure with standard Source(fut)
    val source2 = Source.fromFuture(futTs)
    val t4 = System.nanoTime()
    whenReady(source2.runWith(Sink.seq)) {
      case t +: _ => assert(t < t4)
    }
  }

  "singleLazyAsync" should "create a stream from a future lazily evaluated" in {
    def futTs() = Future.successful(System.nanoTime())

    val source = SourceExt.singleLazyAsync(futTs)
    val t2 = System.nanoTime()

    whenReady(source.runWith(Sink.seq)) {
      case t +: _ =>
        assert(t > t2)
        val t3 = System.nanoTime()
        whenReady(source.runWith(Sink.seq)) {
          case tt +: _ =>
            assert(tt > t3)
            assert(tt > t)
        }
    }

    // Testing failure with standard Source(fut)
    val source2 = Source.fromFuture(futTs)
    val t4 = System.nanoTime()
    whenReady(source2.runWith(Sink.seq)) {
      case t +: _ => assert(t < t4)
    }
  }

  "singleLazy" should "create a stream from a future lazily evaluated" in {

    val source = SourceExt.singleLazy(System.nanoTime())
    val t2 = System.nanoTime()

    whenReady(source.runWith(Sink.seq)) {
      case t +: _ =>
        assert(t > t2)
        val t3 = System.nanoTime()
        whenReady(source.runWith(Sink.seq)) {
          case tt +: _ =>
            assert(tt > t3)
            assert(tt > t)
        }
    }

    // Testing failure with standard Source(fut)
    val source2 = Source.single(System.nanoTime())
    val t4 = System.nanoTime()
    whenReady(source2.runWith(Sink.seq)) {
      case t +: _ => assert(t < t4)
    }
  }

  "constantLazyAsync" should "create a repeated stream from a future lazily evaluated" in {
    val a = 314
    val source = SourceExt.constantLazyAsync(Future.successful(a + 10)).take(1000)

    whenReady(source.runWith(Sink.seq)) { _ should equal (Vector.fill(1000)(a + 10)) }
  }

  "constantLazy" should "create a repeated stream from a future lazily evaluated" in {
    val a = 314
    val source = SourceExt.constantLazy(a + 10).take(1000)

    whenReady(source.runWith(Sink.seq)) { _ should equal (Vector.fill(1000)(a + 10)) }
  }

}
