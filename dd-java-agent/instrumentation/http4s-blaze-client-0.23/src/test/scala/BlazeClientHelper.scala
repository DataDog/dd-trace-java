import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import groovy.lang.Closure

import java.net.URI
import java.util
import scala.concurrent.ExecutionContext

object BlazeClientHelper {
  // This is deprecated in 2.13 so work around it
  @deprecated("", "") class Conv {
    def asScalaIterator[T](c: java.util.Collection[T]) =
      scala.collection.JavaConverters.collectionAsScalaIterable(c)
  }
  object Conv extends Conv

  implicit val runtime: IORuntime   = cats.effect.unsafe.implicits.global
  implicit val ec: ExecutionContext = runtime.compute

  def doSyncRequest(
      method: String,
      uri: URI,
      headers: util.Map[String, String],
      callback: Closure[_]
  ): Int = {
    val status     = doRequest(method, uri, headers)
    val statusCode = status.unsafeRunSync().code
    if (callback != null) {
      callback.call()
    }
    statusCode
  }

  private def doRequest(method: String, uri: URI, headers: util.Map[String, String]): IO[Status] = {
    val http4sUri = Uri.unsafeFromString(uri.toString)
    var req       = Request[IO](Method.fromString(method.toUpperCase).getOrElse(Method.GET), http4sUri)
    req = req.putHeaders(
      Conv
        .asScalaIterator(headers.entrySet())
        .map(e => Header.ToRaw.keyValuesToRaw(e.getKey, e.getValue))
        .toSeq
    )

    BlazeClientBuilder[IO](ec).resource.use { client =>
      val newClient = FollowRedirect(maxRedirects = 5)(client)
      newClient.status(req)
    }
  }
}
