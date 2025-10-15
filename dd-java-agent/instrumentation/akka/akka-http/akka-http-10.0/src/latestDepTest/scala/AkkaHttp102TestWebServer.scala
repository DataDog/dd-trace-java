import AkkaHttpTestWebServer.Binder
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.stream.Materializer

import scala.concurrent.Future

object AkkaHttp102TestWebServer {
  val ServerBuilderBindFlow: Binder = new Binder {
    override def name: String = "server-builder-bind-flow"
    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http()
        .newServerAt("localhost", port)
        .withMaterializer(materializer)
        .bindFlow(AkkaHttpTestWebServer.route)
    }
  }

  val ServerBuilderBind: Binder = new Binder {
    override def name: String = "server-builder-bind"
    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http()
        .newServerAt("localhost", port)
        .withMaterializer(materializer)
        .bind(AkkaHttpTestWebServer.asyncHandler)
    }
  }

  val ServerBuilderBindSync: Binder = new Binder {
    override def name: String = "server-builder-bind-sync"
    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http()
        .newServerAt("localhost", port)
        .withMaterializer(materializer)
        .bindSync(AkkaHttpTestWebServer.syncHandler)
    }
  }

  val ServerBuilderBindHttp2: Binder = new Binder {
    override def name: String = "server-builder-bind-http2"
    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http()
        .newServerAt("localhost", port)
        .withMaterializer(materializer)
        .adaptSettings(AkkaHttpTestWebServer.enableHttp2)
        .bind(AkkaHttpTestWebServer.asyncHandler)
    }
  }
}
