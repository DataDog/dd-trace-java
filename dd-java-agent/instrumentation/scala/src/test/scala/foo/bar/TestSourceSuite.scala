package foo.bar

import org.slf4j.LoggerFactory

import java.net.URI
import scala.io.{BufferedSource, Source}

class TestSourceSuite {

  private val LOGGER = LoggerFactory.getLogger("TestSourceSuite")

  def fromFile(name: String): BufferedSource = {
    LOGGER.debug("Before fromFile {}", name)
    val result = Source.fromFile(name)
    LOGGER.debug("After fromFile {}", result)
    result
  }

  def fromFile(name: String, enc: String): BufferedSource = {
    LOGGER.debug("Before fromFile {} {}", Array(name, enc): _*)
    val result = Source.fromFile(name, enc)
    LOGGER.debug("After fromFile {}", result)
    result
  }

  def fromFile(uri: URI): BufferedSource = {
    LOGGER.debug("Before fromFile {}", uri)
    val result = Source.fromFile(uri)
    LOGGER.debug("After fromFile {}", result)
    result
  }

  def fromFile(uri: URI, enc: String): BufferedSource = {
    LOGGER.debug("Before fromFile {} {}", Array(uri, enc): _*)
    val result = Source.fromFile(uri, enc)
    LOGGER.debug("After fromFile {}", result)
    result
  }

  def fromURI(uri: URI): BufferedSource = {
    LOGGER.debug("Before fromURI {}", uri)
    val result = Source.fromURI(uri)
    LOGGER.debug("After fromURI {}", result)
    result
  }

  def fromURL(name: String, enc: String): BufferedSource = {
    LOGGER.debug("Before fromURL {} {}", Array(name, enc): _*)
    val result = Source.fromURL(name, enc)
    LOGGER.debug("After fromURL {}", result)
    result
  }
}
