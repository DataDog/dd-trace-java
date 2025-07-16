package controllers

import play.api.mvc._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.{HttpURLConnection, URL}

class IastController extends Controller {

  def multipleVulns(id: String): Action[AnyContent] = Action {
    try {
      MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("MD5").digest("hash4".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("RIPEMD128").digest("hash5".getBytes(StandardCharsets.UTF_8))
      Ok("ok")
    } catch {
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def postMultipleVulns(id: String): Action[AnyContent] = Action {
    try {
      MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("MD5").digest("hash4".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("RIPEMD128").digest("hash5".getBytes(StandardCharsets.UTF_8))
      Ok("ok")
    } catch {
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def multipleVulns2(id: String): Action[AnyContent] = Action {
    try {
      MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("MD5").digest("hash4".getBytes(StandardCharsets.UTF_8))
      MessageDigest.getInstance("RIPEMD128").digest("hash5".getBytes(StandardCharsets.UTF_8))
      Ok("ok")
    } catch {
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def sourceParameterGet = Action { request =>
    val table = request.queryString.get("table").map(_.head).getOrElse("")
    try {
      val url  = new URL(table)
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.disconnect()
    } catch {
      case _: Exception => // ignorar
    }
    Ok(s"Request Parameters => source: $table")
  }

  def sourceParameterPost = Action { request =>
    val table = request.body.asFormUrlEncoded.flatMap(_.get("table")).map(_.head).getOrElse("")
    Ok(s"Request Parameters => source: $table")
  }

}
