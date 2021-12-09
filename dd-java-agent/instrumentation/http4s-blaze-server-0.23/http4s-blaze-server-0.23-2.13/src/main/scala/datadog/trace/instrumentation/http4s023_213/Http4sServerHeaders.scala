package datadog.trace.instrumentation.http4s023_213

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import org.http4s.Request

object Http4sServerHeaders {
  private val Getter = new Http4sServerHeaders()

  def getter[F[_]]: Http4sServerHeaders[F] =
    Getter.asInstanceOf[Http4sServerHeaders[F]]
}

class Http4sServerHeaders[F[_]] extends AgentPropagation.ContextVisitor[Request[F]] {
  override def forEachKey(
      carrier: Request[F],
      classifier: AgentPropagation.KeyClassifier
  ): Unit = {
    for (header <- carrier.headers) {
      if (!classifier.accept(header.name.toString.toLowerCase, header.value))
        return
    }
  }
}
