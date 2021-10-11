package datadog.trace.instrumentation.http4s

import cats.data.Kleisli
import cats.effect.Sync
import cats.implicits._
import datadog.trace.bootstrap.instrumentation.api.{AgentScope, AgentSpan}
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status.InternalServerError

import scala.language.higherKinds

object ServerWrapper {

  private type HttpApp[F[_]]           = Kleisli[F, Request[F], Response[F]]
  private type SpanAndDecorator[F[_]]  = (AgentSpan, Http4sHttpServerDecorator[F])
  private type ScopeAndDecorator[F[_]] = (AgentScope, Http4sHttpServerDecorator[F])
  private type ResponseAndMore[F[_]]   = (Response[F], AgentScope, Http4sHttpServerDecorator[F])

  def wrapHttpApp[F[_]: Sync](httpApp: HttpApp[F]): HttpApp[F] =
    Kleisli { req: Request[F] =>
      wrapRequestResponse(req, httpApp)
    }

  def wrapRequestResponse[F[_]: Sync](request: Request[F], httpApp: HttpApp[F]): F[Response[F]] = {
    startSpan(request).redeemWith(
      _ => httpApp(request), { spanAndDecorator =>
        for {
          scopeAndDecorator <- startScope(spanAndDecorator)
          response          <- attachErrorHandler(httpApp(request), scopeAndDecorator)
          _                 <- finishScopeAndSpan(response, scopeAndDecorator)
        } yield response
      }
    )
  }

  private def startSpan[F[_]: Sync](request: Request[F]): F[SpanAndDecorator[F]] = Sync[F].delay {
    val decorator     = Http4sHttpServerDecorator.decorator[F]
    val parentContext = decorator.extract(request)
    val span          = decorator.startSpan(request, parentContext)
    decorator.afterStart(span)
    decorator.onRequest(span, request, request, parentContext)
    (span, decorator)
  }

  private def startScope[F[_]: Sync](
      spanAndDecorator: SpanAndDecorator[F]
  ): F[ScopeAndDecorator[F]] = {
    Sync[F].delay {
      val (span, decorator) = spanAndDecorator
      val scope             = activateSpan(span)
      scope.setAsyncPropagation(true)
      (scope, decorator)
    }
  }

  private def attachErrorHandler[F[_]: Sync](
      body: F[Response[F]],
      traceInfo: ScopeAndDecorator[F]
  ): F[Response[F]] = {
    val (scope, decorator) = traceInfo
    body
      .handleErrorWith(throwable => {
        System.err.println(s"?? HE: ${Thread.currentThread().getName} - ${throwable.getMessage}")
        val span  = scope.span()
        val cause = throwable.getCause
        decorator.onError(span, cause)
        val res = Response[F](InternalServerError)
        decorator.onResponse(span, res)
        decorator.beforeFinish(span)
        scope.close()
        span.finish()
        Sync[F].raiseError(throwable)
      })
  }

  private def finishScopeAndSpan[F[_]: Sync](
      response: Response[F],
      scopeAndDecorator: ScopeAndDecorator[F]
  ): F[Unit] = {
    Sync[F]
      .delay {
        val (scope, decorator) = scopeAndDecorator
        val span               = scope.span()
        decorator.onResponse(span, response)
        decorator.beforeFinish(span)
        scope.close()
        span.finish()
      }
      .handleErrorWith(_ => Sync[F].unit)
  }
}
