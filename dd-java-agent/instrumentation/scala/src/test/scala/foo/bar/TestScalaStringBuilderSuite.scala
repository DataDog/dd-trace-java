package foo.bar

import org.slf4j.LoggerFactory

class TestScalaStringBuilderSuite {

  private val LOGGER = LoggerFactory.getLogger("TestStringBuilderSuite")

  def init(param: String): StringBuilder = {
    LOGGER.debug("Before new string builder {}", param)
    val result: StringBuilder = new StringBuilder(param)
    LOGGER.debug("After new string builder {}", result)
    result
  }

  def init(capacity: Int, param: String): StringBuilder = {
    LOGGER.debug("Before new string builder {}", param)
    val result: StringBuilder = new StringBuilder(capacity, param)
    LOGGER.debug("After new string builder {}", result)
    result
  }

  def init(param: java.lang.StringBuilder): StringBuilder = {
    LOGGER.debug("Before new string builder {}", param)
    val result: StringBuilder = new StringBuilder(param)
    LOGGER.debug("After new string builder {}", result)
    result
  }

  def append(builder: StringBuilder, param: String): Unit = {
    LOGGER.debug("Before string builder append {}", param)
    val result: StringBuilder = builder.append(param)
    LOGGER.debug("After string builder append {}", result)
  }

  def append(builder: StringBuilder, param: StringBuilder): Unit = {
    LOGGER.debug("Before string builder append {}", param)
    val result: StringBuilder = builder.append(param)
    LOGGER.debug("After string builder append {}", result)
  }

  def append(builder: StringBuilder, param: Any): Unit = {
    LOGGER.debug("Before string builder append {}", param)
    val result: StringBuilder = builder.append(param)
    LOGGER.debug("After string builder append {}", result)
  }

  def toString(builder: StringBuilder): String = {
    LOGGER.debug("Before string builder toString {}", builder)
    val result: String = builder.toString
    LOGGER.debug("After string builder toString {}", result)
    result
  }

  def plus(left: String, right: String): String = {
    LOGGER.debug("Before string plus {} {}", Array(left, right): _*)
    val result: String = left + right
    LOGGER.debug("After string plus {}", result)
    result
  }

  def plus(left: String, right: AnyRef): String = {
    LOGGER.debug("Before string plus object {} {}", Array(left, right): _*)
    val result: String = left + right
    LOGGER.debug("After string plus object {}", result)
    result
  }

  def plus(items: Array[AnyRef]): String = {
    LOGGER.debug("Before string plus array {}", items: _*)
    var result: String = ""
    for (item <- items) {
      result += item
    }
    LOGGER.debug("After string plus array {}", result)
    result
  }
}
