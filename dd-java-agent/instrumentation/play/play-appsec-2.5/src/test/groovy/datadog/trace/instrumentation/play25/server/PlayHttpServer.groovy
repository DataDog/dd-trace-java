package datadog.trace.instrumentation.play25.server

import com.typesafe.config.ConfigFactory
import datadog.trace.agent.test.base.HttpServer
import groovy.transform.CompileStatic
import play.api.Application
import play.api.BuiltInComponentsFromContext
import play.api.Environment
import play.api.Mode
import play.api.Play
import play.api.http.HttpErrorHandler
import play.core.server.NettyServerProvider
import play.core.server.ServerConfig
import play.routing.Router
import scala.Option

import java.util.concurrent.TimeoutException

class PlayHttpServer implements HttpServer {
  final Router router
  def server
  def port

  PlayHttpServer(Router router) {
    this.router = router
  }

  @Override
  @CompileStatic
  void start() throws TimeoutException {
    play.api.routing.Router router = this.router.asScala()

    play.api.ApplicationLoader.Context context = play.api.ApplicationLoader.Context$.MODULE$.apply(
      Environment.simple(new File("."), Mode.Test()),
      (scala.Option) scala.None$.MODULE$,
      new play.core.DefaultWebCommands(),
      play.api.Configuration$.MODULE$.apply(ConfigFactory.load())
      )

    BuiltInComponentsFromContext components = new BuiltInComponentsFromContext(context) {
        @Override
        play.api.routing.Router router() {
          router
        }

        @Override
        HttpErrorHandler httpErrorHandler() {
          new play.core.j.JavaHttpErrorHandlerAdapter(TestHttpErrorHandler.INSTANCE)
        }
      }

    Application application = components.application()
    Play.start(application)
    ServerConfig serverConfig = ServerConfig.apply(
      getClass().getClassLoader(),
      new File('.'),
      (scala.Option) Option.apply(0),
      Option.empty(),
      '0.0.0.0',
      Mode.Test(),
      System.getProperties())
    play.core.server.Server server = new NettyServerProvider().createServer(serverConfig, application)

    this.server = server
    this.port = server.httpPort().get()
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
