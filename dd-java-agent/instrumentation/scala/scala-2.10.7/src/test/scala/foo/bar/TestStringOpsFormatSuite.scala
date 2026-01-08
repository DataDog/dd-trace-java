package foo.bar

import org.slf4j.LoggerFactory

class TestStringOpsFormatSuite {

  private val LOGGER = LoggerFactory.getLogger("TestStringOpsFormatSuite")

  /** Test format with scala.math.BigDecimal using %f placeholder.
    */
  def formatBigDecimal(value: String): String = {
    LOGGER.debug("Before formatBigDecimal {}", value)
    val bd             = BigDecimal(value)
    val pattern        = "Value: %f"
    val result: String = pattern.format(bd)
    LOGGER.debug("After formatBigDecimal {}", result)
    result
  }

  /** Test format with scala.math.BigInt using %d placeholder.
    */
  def formatBigInt(value: String): String = {
    LOGGER.debug("Before formatBigInt {}", value)
    val bi             = BigInt(value)
    val pattern        = "Count: %d"
    val result: String = pattern.format(bi)
    LOGGER.debug("After formatBigInt {}", result)
    result
  }

  /** Test format with multiple ScalaNumber arguments.
    */
  def formatMultipleScalaNumbers(decimal: String, integer: String): String = {
    LOGGER.debug("Before formatMultipleScalaNumbers {} {}", Array(decimal, integer): _*)
    val bd             = BigDecimal(decimal)
    val bi             = BigInt(integer)
    val pattern        = "Decimal: %f, Integer: %d"
    val result: String = pattern.format(bd, bi)
    LOGGER.debug("After formatMultipleScalaNumbers {}", result)
    result
  }

  /** Test format with mixed ScalaNumber and regular arguments.
    */
  def formatMixed(decimal: String, text: String): String = {
    LOGGER.debug("Before formatMixed {} {}", Array(decimal, text): _*)
    val bd             = BigDecimal(decimal)
    val pattern        = "Value: %f, Text: %s"
    val result: String = pattern.format(bd, text)
    LOGGER.debug("After formatMixed {}", result)
    result
  }

  /** Test format with regular String arguments (no unwrapping needed).
    */
  def formatString(left: String, right: String): String = {
    LOGGER.debug("Before formatString {} {}", Array(left, right): _*)
    val pattern        = "Left: %s, Right: %s"
    val result: String = pattern.format(left, right)
    LOGGER.debug("After formatString {}", result)
    result
  }
}
