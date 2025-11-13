import java.util
import java.util.function.Supplier
import scala.collection.JavaConverters._

class ScalaPropagation extends Supplier[util.List[String]] {

  override def get(): util.List[String] = List("plus", "s", "f", "raw", "formatBigDecimal").asJava

  def plus(param: String): String = param + param

  def s(param: String): String = s"s interpolation: $param"

  def f(param: String): String = f"f interpolation: $param"

  def raw(param: String): String = raw"raw interpolation: $param"

  /** Test case to reproduce IllegalFormatConversionException from original stack trace.
    * Root cause: Scala's format() calls unwrapArg() which converts BigDecimal -> Double.
    * IAST receives BigDecimal without unwrapping, causing IllegalFormatConversionException.
    */
  def formatBigDecimal(param: String): String = {
    val numericValue = BigDecimal(param)
    "Value: %f".format(numericValue)
  }
}
