package com.mfglabs.stream
package extensions.elasticsearch

import org.elasticsearch.action.{ActionListener, ListenableActionFuture}
import scala.concurrent.{Future, Promise}

object EsHelper {
  implicit class RichListenableActionFuture[T](laf: ListenableActionFuture[T]) {
    def asScala: Future[T] = {
      val p = Promise[T]()

      laf.addListener(new ActionListener[T] {
          def onResponse(response: T) = p.success(response)
          def onFailure(e: Throwable) = p.failure(e)
          def onFailure(e: Exception) = p.failure(e)
        }
      )

      p.future
    }
  }
}

