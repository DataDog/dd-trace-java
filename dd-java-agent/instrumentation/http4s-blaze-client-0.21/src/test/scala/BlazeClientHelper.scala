import cats.effect.{ContextShift, IO}
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import groovy.lang.Closure

import java.net.URI
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.DurationInt

object BlazeClientHelper {

  implicit val ec: ExecutionContext = global
  implicit val cs: ContextShift[IO] = IO.contextShift(global)

  def doSyncRequest(method: String, uri: URI, headers: util.Map[String, String], callback: Closure[_]): Int = {
    val status = doRequest(method, uri, headers)
    val statusCode = status.unsafeRunSync().code
    if (callback != null) {
      callback.call()
    }
    statusCode
  }

  def doTimedRequest(method: String, uri: URI, headers: util.Map[String, String], callback: Closure[_]): Int = {
    val status = doRequest(method, uri, headers)
    val statusCode = status.unsafeRunTimed(2.seconds).get.code
    if (callback != null) {
      callback.call()
    }
    statusCode
  }

  def doFutureRequest(method: String, uri: URI, headers: util.Map[String, String], callback: Closure[_]): Int = {
    val status = doRequest(method, uri, headers)
    val statusCode = status.unsafeToFuture()

    statusCode.onComplete (_ =>
      if (callback != null) {
        callback.call()
      }
    )

    Await.result(statusCode, 4 seconds).code
  }

  def doAsyncRequest(method: String, uri: URI, headers: util.Map[String, String], callback: Closure[_]): Int = {
    val status = doRequest(method, uri, headers)

    val myFuture = new CompletableFuture[Int]()

    status.unsafeRunAsync(cb => {
      cb.foreach(f => {
        if (callback != null) {
          callback.call()
        }
        myFuture.complete(f.code)
      })
    })

    myFuture.get(4, TimeUnit.SECONDS)
  }

  private def doRequest(method: String, uri: URI, headers: util.Map[String, String]): IO[Status] = {
    val http4sUri = Uri.unsafeFromString(uri.toString)
    var req       = Request[IO](Method.GET, http4sUri)
    if (method.equalsIgnoreCase("GET")) {
      req = Request[IO](Method.GET, http4sUri)
    } else if (method.equalsIgnoreCase("POST")) {
      req = Request[IO](Method.POST, http4sUri)
    } else if (method.equalsIgnoreCase("PUT")) {
      req = Request[IO](Method.PUT, http4sUri)
    } else if (method.equalsIgnoreCase("HEAD")) {
      req = Request[IO](Method.HEAD, http4sUri)
    }

    headers.forEach((key, value) => req = req.putHeaders(Header(key, value)))

    BlazeClientBuilder[IO](global).resource.use { client =>
      val newClient = FollowRedirect(maxRedirects = 5)(client)
      newClient.status(req)
    }
  }
}
