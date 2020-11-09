import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import scala.concurrent.{ExecutionContext, Future}

object ConcurrentTestWebServer {
  def start(port: Int): ConcurrentTestWebServer = {
    new ConcurrentTestWebServer(port)
  }
}

class ConcurrentTestWebServer(private val port: Int) {
  // Create the actor system with fewer threads to increase the likelihood
  // of work from different requests getting processed on the same thread
  // right after each other
  private implicit val system: ActorSystem = ActorSystem(
    s"system-$port",
    ConfigFactory.parseString("""
    |akka {
    |  actor {
    |    default-dispatcher {
    |      executor = "fork-join-executor"
    |      fork-join-executor {
    |        parallelism-min = 4
    |        parallelism-max = 4
    |      }
    |    }
    |  }
    |}
    |""".stripMargin)
  )
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private implicit val excutionContext: ExecutionContext = system.dispatcher

  private val tracer = AgentTracer.get()
  private val route = get {
    pathPrefix("akka-http") {
      path("ping" / IntNumber) { id =>
        val traceId = tracer.activeSpan().getTraceId
        complete(s"pong $id -> $traceId")
      } ~ path("fing" / IntNumber) { id =>
        // force the response to happen on another thread or in another context
        onSuccess(Future {
          Thread.sleep(10); id
        }) { fid =>
          val traceId = tracer.activeSpan().getTraceId
          complete(s"fong $fid -> $traceId")
        }
      }
    }
  }

  private val bindingFuture = Http().bindAndHandle(route, "localhost", port)

  def stop(): Unit = {
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
