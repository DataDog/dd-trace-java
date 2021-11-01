package datadog.trace.instrumentation.http4s022_212

import datadog.trace.bootstrap.instrumentation.api.{
  AgentPropagation,
  URIDataAdapter,
  UTF8BytesString
}
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator
import org.http4s.{Request, Response}

object Http4sHttpServerDecorator {
  val HTTP4S_HTTP_REQUEST: UTF8BytesString =
    UTF8BytesString.create("http4s-http.request")
  val HTTP4S_HTTP_SERVER: UTF8BytesString =
    UTF8BytesString.create("http4s-http-server")
  private val Decorator = new Http4sHttpServerDecorator()

  def decorator[F[_]]: Http4sHttpServerDecorator[F] =
    Decorator.asInstanceOf[Http4sHttpServerDecorator[F]]
}

final class Http4sHttpServerDecorator[F[_]]
    extends HttpServerDecorator[Request[F], Request[F], Response[F], Request[F]] {
  override protected def instrumentationNames: Array[String] =
    Array[String]("http4s")

  override protected def component: CharSequence =
    Http4sHttpServerDecorator.HTTP4S_HTTP_SERVER

  override protected def getter: AgentPropagation.ContextVisitor[Request[F]] =
    Http4sServerHeaders.getter

  override def spanName: CharSequence =
    Http4sHttpServerDecorator.HTTP4S_HTTP_REQUEST

  override protected def method(request: Request[F]): String =
    request.method.name

  override protected def url(request: Request[F]): URIDataAdapter =
    new Http4sURIAdapter(request)

  override protected def peerHostIP(request: Request[F]): String = null

  override protected def peerPort(request: Request[F]): Int = { // TODO : add support of client/peer port
    0
  }

  override protected def status(httpResponse: Response[F]): Int =
    httpResponse.status.code
}
