package foo.bar

import org.slf4j.LoggerFactory

class TestStringInterpolationSuite {

  private val LOGGER = LoggerFactory.getLogger("TestStringOpsSuite")

  def f(left: AnyRef, right: AnyRef): String = {
    LOGGER.debug("Before f {} {}", Array(left, right): _*)
    val result: String = f"Left is '$left' and right is '$right'"
    LOGGER.debug("After f {}", result)
    result
  }

  def s(left: AnyRef, right: AnyRef): String = {
    LOGGER.debug("Before s {} {}", Array(left, right): _*)
    val result: String = s"Left is '$left' and right is '$right'"
    LOGGER.debug("After s {}", result)
    result
  }

  def raw(left: AnyRef, right: AnyRef): String = {
    LOGGER.debug("Before raw {} {}", Array(left, right): _*)
    val result: String = raw"Left is '$left' and right is '$right'"
    LOGGER.debug("After raw {}", result)
    result
  }

  def leadingChunk(value: AnyRef): String = {
    LOGGER.debug("Before leadingChunk {}", value)
    val result: String = raw"$value: is located at the beginning of the string"
    LOGGER.debug("After leadingChunk {}", result)
    result
  }

  def trailingChunk(value: AnyRef): String = {
    LOGGER.debug("Before trailingChunk {}", value)
    val result: String = raw"The value is located at the end of the string: $value"
    LOGGER.debug("After trailingChunk {}", result)
    result
  }

}
