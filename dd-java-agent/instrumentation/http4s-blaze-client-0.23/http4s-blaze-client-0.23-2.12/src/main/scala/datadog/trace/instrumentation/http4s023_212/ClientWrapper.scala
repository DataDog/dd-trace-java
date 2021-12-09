package datadog.trace.instrumentation.http4s023_212

import cats.effect.{Resource, Sync}
import cats.implicits._
import datadog.trace.bootstrap.instrumentation.api.{AgentSpan, AgentTracer}
import org.http4s.{Request, Response}
import org.http4s.client.Client

object ClientWrapper {
  private type RequestAndSpanAndDecorator[F[_]] =
    (Request[F], AgentSpan, Http4sHttpClientDecorator[F])

  def resource[F[_]: Sync](resource: Resource[F, Client[F]]): Resource[F, Client[F]] =
    resource.map(wrapClientResource(_))

  private def wrapClientResource[F[_]: Sync](client: Client[F]): Client[F] =
    Client { req: Request[F] =>
      Resource {
        for {
          rsd <- startSpanAndInjectRequest(req)
          (nreq, span, decorator) = rsd
          resource <- client.run(nreq).allocated
          _        <- complete(span, decorator, resource._1)
        } yield resource
      }
    }

  private def startSpanAndInjectRequest[F[_]: Sync](
      request: Request[F]
  ): F[RequestAndSpanAndDecorator[F]] =
    Sync[F].delay {
      val decorator = Http4sHttpClientDecorator.decorator[F]
      val span      = AgentTracer.startSpan(Http4sHttpClientDecorator.HTTP4S_HTTP_REQUEST)
      decorator.afterStart(span)
      decorator.onRequest(span, request)

      val setter = Http4sClientHeaders.setter(request)
      AgentTracer.propagate().inject(span, request, setter)
      span.startThreadMigration()

      (setter.getRequest, span, decorator)
    }

  private def complete[F[_]: Sync](
      span: AgentSpan,
      decorator: Http4sHttpClientDecorator[F],
      response: Response[F]
  ): F[Response[F]] = {
    Sync[F].delay {
      span.finishThreadMigration()
      decorator.onResponse(span, response)
      decorator.beforeFinish(span)
      span.finish()
      response
    }
  }
}
