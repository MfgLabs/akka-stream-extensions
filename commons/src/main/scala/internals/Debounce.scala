package com.mfglabs.stream

import akka.stream.stage._
import akka.stream._

import scala.concurrent.duration.FiniteDuration

/**
 * Implementation of a Debouncer
 * The precision is not great, something around ~100ms
 * It does not drop elements but wrap it in one of two classes
 *   - Debounced.Ok: the elemnt was not seen since at least `per` duration.
 *   - Debounced.Ko: the elemnt was seen less than `per` duration ago.
 */
case class Debounce[T](per: FiniteDuration, toHash: T => String) extends GraphStage[FlowShape[T, Debounced[T]]] {
  import Debounced._

  require(per.toNanos > 0, "per time must be > 0")

  val in  = Inlet[T]("Debounce.in")
  val out = Outlet[Debounced[T]]("Debounce.out")

  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new TimerGraphStageLogic(shape) with OutHandler with InHandler {
      val emitted = java.util.Collections.newSetFromMap(
        new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]
      )

      override def onPush(): Unit = {
        val elem = grab(in)
        val hash = toHash(elem)

        if( emitted.add(hash) ) {
          push(out, Ok(elem))
          scheduleOnce(hash, per)
        } else push(out, Ko(elem))
      }

      override protected def onTimer(key: Any): Unit = {
        val _ = emitted.remove(key.toString)
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
  }

  override def toString = "Debounce"
}


sealed trait Debounced[T]
object Debounced {
  case class Ok[T](elem: T) extends Debounced[T]
  case class Ko[T](elem: T) extends Debounced[T]
}
