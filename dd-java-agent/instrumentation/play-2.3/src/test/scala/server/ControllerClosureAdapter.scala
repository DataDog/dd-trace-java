package server

import datadog.trace.agent.test.base.HttpServerTest
import groovy.lang.Closure
import play.api.mvc.Result
import scala.concurrent.Future

class ControllerClosureAdapter(response: Result) extends Closure[Result]((): Unit) {
  override def call(): Result =
    response.withHeaders(
      (HttpServerTest.getIG_RESPONSE_HEADER, HttpServerTest.getIG_RESPONSE_HEADER_VALUE)
    )
}

class BlockClosureAdapter(block: () => Result) extends Closure[Result]((): Unit) {
  override def call(): Result =
    block().withHeaders(
      (HttpServerTest.getIG_RESPONSE_HEADER, HttpServerTest.getIG_RESPONSE_HEADER_VALUE)
    )
}

class AsyncControllerClosureAdapter(response: Future[Result])
    extends Closure[Future[Result]]((): Unit) {
  import scala.concurrent.ExecutionContext.Implicits.global
  override def call(): Future[Result] =
    response.map(
      _.withHeaders(
        (HttpServerTest.getIG_RESPONSE_HEADER, HttpServerTest.getIG_RESPONSE_HEADER_VALUE)
      )
    )
}

class AsyncBlockClosureAdapter(block: () => Future[Result])
    extends Closure[Future[Result]]((): Unit) {
  import scala.concurrent.ExecutionContext.Implicits.global
  override def call(): Future[Result] =
    block().map(
      _.withHeaders(
        (HttpServerTest.getIG_RESPONSE_HEADER, HttpServerTest.getIG_RESPONSE_HEADER_VALUE)
      )
    )
}
