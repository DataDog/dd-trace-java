package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.base.HttpServer
import play.ApplicationLoader
import play.BuiltInComponents
import play.BuiltInComponentsFromContext
import play.Environment
import play.Mode
import play.api.Configuration
import play.api.Play
import play.core.server.AkkaHttpServerProvider
import play.core.server.ServerConfig
import play.http.HttpErrorHandler
import play.mvc.EssentialFilter
import play.routing.Router
import scala.Option

import java.util.concurrent.TimeoutException
import java.util.function.Function

class PlayHttpServer implements HttpServer {
  final Function<BuiltInComponents, Router> router
  HttpErrorHandler httpErrorHandler
  def application
  def server
  def port

  PlayHttpServer(Function<BuiltInComponents, Router> router) {
    this(router, null)
  }

  PlayHttpServer(Function<BuiltInComponents, Router> router, HttpErrorHandler httpErrorHandler) {
    this.router = router
    this.httpErrorHandler = httpErrorHandler
  }

  @Override
  void start() throws TimeoutException {
    def environment = Environment.simple()
    def context = ApplicationLoader.create(environment)
    application = new BuiltInComponentsFromContext(context) {
        @Override
        Router router() {
          router.apply(this)
        }

        @Override
        List<EssentialFilter> httpFilters() {
          Collections.emptyList()
        }

        @Override
        HttpErrorHandler httpErrorHandler() {
          if (httpErrorHandler != null) {
            return httpErrorHandler
          }
          return super.httpErrorHandler()
        }
      }.application().asScala()
    Play.start(application)

    def config = new ServerConfig(
      new File("."),
      Option.apply(0),
      Option.empty(),
      "0.0.0.0",
      Mode.PROD.asScala(),
      System.getProperties(),
      Configuration.load(environment.asScala())
      )
    server = new AkkaHttpServerProvider().createServer(config, application)
    port = server.httpPort().get()
  }

  @Override
  void stop() {
    server.stop()
  }

  @Override
  URI address() {
    return new URI("http://localhost:$port/")
  }
}
