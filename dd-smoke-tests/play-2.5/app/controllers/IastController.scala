package controllers

import play.api.mvc._
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

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
}
