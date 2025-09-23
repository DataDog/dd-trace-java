package datadog.trace.instrumentation.play26.server.latestdep

import datadog.trace.agent.test.base.HttpServer
import groovy.transform.CompileStatic
import play.Mode
import play.api.ApplicationLoader
import play.api.BuiltInComponents
import play.api.BuiltInComponentsFromContext
import play.api.Configuration
import play.api.Environment
import play.api.Play
import play.api.inject.DefaultApplicationLifecycle
import play.api.routing.Router
import play.core.j.JavaHttpErrorHandlerAdapter
import play.core.server.AkkaHttpServerProvider
import play.core.server.Server
import play.core.server.ServerConfig
import play.http.HttpErrorHandler
import play.mvc.EssentialFilter
import scala.None$
import scala.Option
import scala.collection.Map$
import scala.collection.immutable.Map
import scala.collection.immutable.Seq

import java.util.concurrent.TimeoutException
import java.util.function.Function

@CompileStatic
class PlayHttpServerScala implements HttpServer {
  final Function<BuiltInComponents, Router> router
  HttpErrorHandler httpErrorHandler
  def application
  def server
  def port

  PlayHttpServerScala(Function<BuiltInComponents, Router> router, HttpErrorHandler httpErrorHandler) {
    this.router = router
    this.httpErrorHandler = httpErrorHandler
  }

  @Override
  void start() throws TimeoutException {
    Environment environment = Environment.simple(new File('.'), play.api.Mode.Test$.MODULE$)
    ApplicationLoader.Context context = ApplicationLoader.Context$.MODULE$.create(
      environment,
      Map$.MODULE$.empty() as Map,
      new DefaultApplicationLifecycle(),
      None$.empty()
      )
    application = new BuiltInComponentsFromContext(context) {
        @Override
        Router router() {
          router.apply(this)
        }

        @Override
        Seq<EssentialFilter> httpFilters() {
          (Seq) scala.collection.immutable.Seq$.MODULE$.empty()
        }

        @Override
        play.api.http.HttpErrorHandler httpErrorHandler() {
          if (httpErrorHandler != null) {
            return new JavaHttpErrorHandlerAdapter(httpErrorHandler)
          }
          super.httpErrorHandler()
        }
      }.application()
    Play.start(this.application as play.api.Application)

    def config = new ServerConfig(
      new File('.'),
      Option.apply(0) as Option,
      Option.empty() as Option,
      '0.0.0.0',
      Mode.PROD.asScala(),
      System.getProperties(),
      Configuration.load(environment)
      )

    Server server = new AkkaHttpServerProvider().createServer(config,
      application as play.api.Application)
    this.server = server
    port = server.httpPort().get()
  }

  @Override
  void stop() {
    (this.server as Server).stop()
  }

  @Override
  URI address() {
    new URI("http://localhost:$port/")
  }
}
