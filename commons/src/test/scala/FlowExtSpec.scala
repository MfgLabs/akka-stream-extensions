package com.mfglabs.stream

import java.io.File

import akka.actor.ActorSystem
import akka.stream.{OverflowStrategy, ActorFlowMaterializerSettings, ActorFlowMaterializer}
import akka.stream.scaladsl._
import akka.util.ByteString
import org.scalatest._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time._
import concurrent.ScalaFutures

import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.concurrent.duration._

class FlowExtSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val mat = ActorFlowMaterializer(ActorFlowMaterializerSettings(system).withInputBuffer(16, 16))
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(3, Minutes), interval = Span(20, Millis))

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val ecBlocking = ExecutionContextForBlockingOps(scala.concurrent.ExecutionContext.Implicits.global)

  def bigFile = new File(getClass.getResource("/big.txt").getPath)

  def fireIn(duration: FiniteDuration): Future[Unit] = {
    val p = Promise[Unit]
    system.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    p.future
  }

  "mapAsyncWithOrderedSideEffect" should "perform side-effect in order" in {
    val range = 1 to 30
    @volatile var seq = Seq.empty[Int]
    Source(range).via(FlowExt.mapAsyncWithOrderedSideEffect { el =>
      Future {
        if (el % 2 == 0) {
          Future {
            seq = seq :+ el
            el
          }
        }
        else fireIn(500 millis).map { _ =>
          seq = seq :+ el
          el
        }
      }.flatMap(identity)
    })
    .runForeach(_ => ())
    .futureValue

    seq.length shouldEqual range.length
    seq shouldEqual range.toSeq
  }

  "withHead" should "allows to define a flow according the first element of the upstream" in {
    val range = 10 to 97
    val futSeq = Source(range)
      .via(FlowExt.withHead(includeHeadInUpStream = true)(first => Flow[Int].map(i => first + i)))
      .runWith(SinkExt.collect)

    whenReady(futSeq) { seq =>
      seq shouldEqual range.map(i => range.head + i).toSeq
    }
  }

  "zipWithIndex" should "zipWithIndex a small stream" in {
    def treat(xs: List[String]): Future[Seq[(String, Long)]] =
      Source(xs)
        .via(FlowExt.zipWithIndex)
        .runWith(SinkExt.collect)

    whenReady(treat(List("a", "b", "c", "d")))(
      _ shouldEqual List(("a",0), ("b",1), ("c",2), ("d",3)))

    whenReady(treat(List.empty))(_ shouldEqual List.empty)

    whenReady(treat(List("a")))(_ shouldEqual List(("a",0)))
  }

  it should "zipWithIndex a big stream" in {
    val stream = SourceExt.fromFile(bigFile, maxChunkSize = 1024)
    val futZipped: Future[Seq[(ByteString, Long)]] = stream.via(FlowExt.zipWithIndex).runWith(SinkExt.collect)

    val futExpectedZipped =
      SourceExt.fromFile(bigFile, maxChunkSize = 1024)
        .runWith(SinkExt.collect)
        .map(_.zipWithIndex)
        .map { _.map {case (bs, i) => (bs, i.toLong) } }

    whenReady(futZipped zip futExpectedZipped) { case (zipped, expectedZipped) =>
      zipped shouldEqual expectedZipped
    }
  }

  "rechunkByteStringBySeparator" should "convert a small byte stream to a string stream chunked by lines" in {
    def treat(xs: List[String]): Future[Seq[String]] =
      Source(xs).map(ByteString.apply)
        .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 1 * 1024 * 1024))
        .map(_.utf8String)
        .runWith(SinkExt.collect)

    whenReady(treat(List("bonjour\nbon.soir\n", "matin\n\nmidi\nsoir", "\n", "seul\n", "\nseul\n", "toutseul"))) { res =>
      res shouldEqual List("bonjour", "bon.soir", "matin", "", "midi", "soir", "seul", "", "seul", "toutseul")
    }

    whenReady(treat(List("bon", "jour\nbon", "soir\n"))) { res =>
      res shouldEqual List("bonjour", "bonsoir")
    }

    whenReady(treat(List("\n"))) { res =>
      res shouldEqual List("")
    }

    whenReady(treat(List.empty[String])) { res =>
      res shouldEqual List.empty[String]
    }
  }

  it should "convert a big byte stream to a string stream chunked by lines" in {
    val stream = SourceExt.fromFile(bigFile, maxChunkSize = 5 * 1024 * 1024)
    val futLines: Future[Seq[String]] =
      stream
        .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 1 * 1024 * 1024))
        .map(_.utf8String).runWith(SinkExt.collect)

    val futExpectedLines =
      SourceExt.fromFile(bigFile)
        .runFold(ByteString.empty)(_ ++ _)
        .map(_.compact.utf8String)
        .map(_.split("\n").to[scala.collection.immutable.Seq])

    whenReady(futLines zip futExpectedLines) { case (lines, expectedLines) =>
      lines shouldEqual expectedLines
    }
  }

  "rechunkByteStringBySize" should "rechunk a stream of bytes to bigger chunks" in {
    val stream = SourceExt.fromFile(bigFile, maxChunkSize = 1024)
    val futChunks: Future[Seq[ByteString]] = stream.via(FlowExt.rechunkByteStringBySize(1024 * 5)).runWith(SinkExt.collect)

    val futExpectedChunks: Future[Seq[ByteString]] =
      SourceExt.fromFile(bigFile, maxChunkSize = 1024)
        .grouped(5)
        .map(_.fold(ByteString.empty)(_ ++ _))
        .runWith(SinkExt.collect)

    whenReady(futChunks zip futExpectedChunks) { case (chunks, expectedChunks) =>
      chunks shouldEqual expectedChunks
    }
  }

  it should "rechunk a stream of bytes to smaller chunks" in {
    val stream = SourceExt.fromFile(bigFile, maxChunkSize = 1024)
    val futChunks: Future[Seq[ByteString]] = stream.via(FlowExt.rechunkByteStringBySize(1024 / 2)).runWith(SinkExt.collect)

    val futExpectedChunks: Future[Seq[ByteString]] =
      SourceExt.fromFile(bigFile, maxChunkSize = 1024 / 2)
        .runWith(SinkExt.collect)

    whenReady(futChunks zip futExpectedChunks) { case (chunks, expectedChunks) =>
      chunks shouldEqual expectedChunks
    }
  }


  "rateLimiter" should "rate limit a downstream" in {
    val futSeqTs = Source.apply(for (i <- 1 to 5) yield i)
      .via(FlowExt.rateLimiter(1 second))
      .map { _ => System.currentTimeMillis() }
      .runWith(SinkExt.collect)

    val tsSeq = Await.result(futSeqTs, 10 seconds)

    tsSeq should have length 5

    tsSeq.foldLeft(0L) { (lastTs, ts) =>
      if (lastTs != 0L) {
        assert(ts - lastTs >= 990) // should be 1000
      }
      ts
    }
  }

  "customStatefulProcessor" should "fold/unfold a stream according to a user defined function" in {
    val futSeq = Source(1 to 97).via(
      FlowExt.customStatefulProcessor[Int, Vector[Int], Vector[Int]](Vector.empty)( // emit chunk of 10 elems, and potentially a smaller final chunk
        (state, elem) => {
          if (state.length < 10) (Some(state :+ elem), Vector.empty)
          else (Some(Vector(elem)), Vector(state))
        },
        lastPushIfUpstreamEnds = b => Vector(b)
      )
    )
    .runWith(SinkExt.collect)

    val expectedSeq = (1 to 97).grouped(10).toList

    whenReady(futSeq) { seq =>
      seq shouldEqual expectedSeq
    }
  }

  it should "end the stream upon a condition" in {
    val futSeq = Source(1 to 97).via(
      FlowExt.customStatefulProcessor[Int, Vector[Int], Vector[Int]](Vector.empty)(
        (state, elem) => {
          if (state.length == 50) (None, Vector(state))
          else (Some(state :+ elem), Vector.empty)
        }
      )
    )
    .runWith(SinkExt.collect)

    whenReady(futSeq) { seq =>
      seq should have length 1
      seq.head shouldEqual (1 to 50).toVector
    }
  }

  "customStatelessProcessor" should "expand a stream according to a user defined function" in {
    val futSeq = Source(1 to 97).via(
      FlowExt.customStatelessProcessor[Int, Int](elem => (Vector(elem, elem), false))
    )
    .runWith(SinkExt.collect)

    val expectedSeq = (1 to 97).flatMap(el => Seq(el, el))

    whenReady(futSeq) { seq =>
      seq shouldEqual expectedSeq
    }
  }

  "zipWithConstantLazyAsync" should "zip a flow with a constant future value evaluated lazily" in {
    def futTs() = Future.successful(System.nanoTime())

    val range = 1 to 97

    val source = Source(range).via(FlowExt.zipWithConstantLazyAsync(futTs))
    val t2 = System.nanoTime()

    whenReady(source.runWith(SinkExt.collect)) { seq =>
      seq.map(_._1) shouldEqual range.toSeq
      assert(seq.head._2 > t2)
      seq.forall(_._2 == seq.head._2) shouldBe true
    }
  }

  "repeatEach" should "repeat each element of a source" in {
    val range = Source(1 to 10)

    val source = Source(1 to 10).via(FlowExt.repeatEach(3))

    whenReady(source.runWith(SinkExt.collect)) { seq =>
      seq should equal ((1 to 10).flatMap(i => List(i, i, i)))
    }
  }

  "takeWhile" should "end a stream when a condition is met" in {
    val range = 1 to 100

    val source = Source(range).via(FlowExt.takeWhile(_ == 42))

    whenReady(source.runWith(SinkExt.collect)) { seq =>
      seq shouldEqual range.toSeq.takeWhile(_ == 42)
    }
  }

}
