package foo.bar

import org.slf4j.LoggerFactory

import scala.util.Random

class TestRandomSuite {

  private val LOGGER = LoggerFactory.getLogger("TestRandomSuite")

  private val random: Random = new Random()

  def nextBoolean: Boolean = {
    LOGGER.debug("Before nextBoolean")
    val result = random.nextBoolean
    LOGGER.debug("After nextBoolean {}", result)
    result
  }

  def nextInt: Int = {
    LOGGER.debug("Before nextInt")
    val result = random.nextInt
    LOGGER.debug("After nextInt {}", result)
    result
  }

  def nextInt(i: Int): Int = {
    LOGGER.debug("Before nextInt {}", i)
    val result = random.nextInt(i)
    LOGGER.debug("After nextInt {}", result)
    result
  }

  def nextLong: Long = {
    LOGGER.debug("Before nextLong")
    val result = random.nextLong
    LOGGER.debug("After nextLong {}", result)
    result
  }

  def nextFloat: Float = {
    LOGGER.debug("Before nextFloat")
    val result = random.nextFloat
    LOGGER.debug("After nextFloat {}", result)
    result
  }

  def nextDouble: Double = {
    LOGGER.debug("Before nextDouble")
    val result = random.nextDouble
    LOGGER.debug("After nextDouble {}", result)
    result
  }

  def nextGaussian: Double = {
    LOGGER.debug("Before nextGaussian")
    val result = random.nextGaussian
    LOGGER.debug("After nextDouble {}", result)
    result
  }

  def nextBytes(bytes: Array[Byte]): Array[Byte] = {
    LOGGER.debug("Before nextBytes {}", bytes)
    random.nextBytes(bytes)
    LOGGER.debug("After nextBytes")
    bytes
  }

  def nextString(length: Int): String = {
    LOGGER.debug("Before nextString {}", length)
    val result = random.nextString(length)
    LOGGER.debug("After nextString")
    result
  }

  def nextPrintableChar(): Char = {
    LOGGER.debug("Before nextPrintableChar")
    val result = random.nextPrintableChar()
    LOGGER.debug("After nextPrintableChar")
    result
  }

}
