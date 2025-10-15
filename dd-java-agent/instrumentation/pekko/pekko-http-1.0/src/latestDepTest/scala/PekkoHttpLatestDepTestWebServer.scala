import PekkoHttpTestWebServer.Binder
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer

import scala.concurrent.Future

object PekkoHttpLatestDepTestWebServer {
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
        .bindFlow(PekkoHttpTestWebServer.route)
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
        .bind(PekkoHttpTestWebServer.asyncHandler)
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
        .bindSync(PekkoHttpTestWebServer.syncHandler)
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
        .adaptSettings(PekkoHttpTestWebServer.enableHttp2)
        .bind(PekkoHttpTestWebServer.asyncHandler)
    }
  }
}
