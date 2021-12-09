package datadog.trace.instrumentation.http4s023_212

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import org.http4s.{Header, Request}

object Http4sClientHeaders {
  def setter[F[_]](request: Request[F]): Http4sClientHeaders[F] =
    new Http4sClientHeaders[F](request)
}

class Http4sClientHeaders[F[_]](private var request: Request[F])
    extends AgentPropagation.Setter[Request[F]] {

  override def set(carrier: Request[F], key: String, value: String): Unit = {
    request = request.withHeaders(
      request.headers.put(Header.ToRaw.keyValuesToRaw(key, value))
    )
  }

  def getRequest: Request[F] = request
}
