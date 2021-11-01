import cats.effect.{ContextShift, IO}
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import org.typelevel.ci.CIString

import java.net.URI
import java.util
import scala.concurrent.ExecutionContext.global

object BlazeClientHelper {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)

  def doRequest(method: String, uri: URI, headers: util.Map[String, String]): Int = {

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

    headers.forEach((key, value) => req = req.putHeaders(Header.Raw(CIString(key), value)))

    val status = BlazeClientBuilder[IO](global).resource.use { client =>
      val newClient = FollowRedirect(maxRedirects = 5)(client)
      newClient.status(req)
    }

    // TODO use async...
    status.unsafeRunSync().code
  }
}
