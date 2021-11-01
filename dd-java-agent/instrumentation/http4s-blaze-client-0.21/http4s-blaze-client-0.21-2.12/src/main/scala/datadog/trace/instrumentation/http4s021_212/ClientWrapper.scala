package datadog.trace.instrumentation.http4s021_212

import cats.effect.{ConcurrentEffect, Resource, Sync}
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import datadog.trace.bootstrap.instrumentation.api.{AgentScope, AgentTracer}
import org.http4s.{Request, Response}
import org.http4s.client.Client
import scala.language.higherKinds

object ClientWrapper {
  private type RequestAndScopeAndDecorator[F[_]] =
    (Request[F], AgentScope, Http4sHttpClientDecorator[F])

  def resource[F[_]: ConcurrentEffect](resource: Resource[F, Client[F]]): Resource[F, Client[F]] =
    resource.map(wrapClientResource(_))

  private def wrapClientResource[F[_]: ConcurrentEffect](client: Client[F]): Client[F] =
    Client { req: Request[F] =>
      for {
        requestAndScopeAndDecorator <- Resource.liftF(startScopeAndWrapRequest(req))
        response                    <- client.run(requestAndScopeAndDecorator._1)
        newResponse <- Resource.liftF(
          ConcurrentEffect[F].handleErrorWith(
            complete(requestAndScopeAndDecorator._2, requestAndScopeAndDecorator._3, response)
          )(
            _ => Sync[F].delay(response)
          )
        )
      } yield newResponse
    }

  private def startScopeAndWrapRequest[F[_]: Sync](
      request: Request[F]
  ): F[RequestAndScopeAndDecorator[F]] =
    Sync[F].delay {
      val decorator = Http4sHttpClientDecorator.decorator[F]
      val span      = AgentTracer.startSpan(Http4sHttpClientDecorator.HTTP4S_HTTP_REQUEST)
      decorator.afterStart(span)
      decorator.onRequest(span, request)

      val setter = Http4sClientHeaders.setter(request)
      AgentTracer.propagate().inject(span, request, setter)

      val scope = activateSpan(span)
      scope.setAsyncPropagation(true)

      (setter.getRequest, scope, decorator)
    }

  private def complete[F[_]: Sync](
      scope: AgentScope,
      decorator: Http4sHttpClientDecorator[F],
      response: Response[F]
  ): F[Response[F]] = {
    Sync[F].delay {
      val span = scope.span()
      decorator.onResponse(span, response)
      decorator.beforeFinish(span)
      scope.close()
      span.finish()
      response
    }
  }
}
