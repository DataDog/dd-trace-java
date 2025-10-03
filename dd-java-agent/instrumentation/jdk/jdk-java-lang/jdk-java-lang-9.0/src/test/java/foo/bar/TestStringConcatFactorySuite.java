package foo.bar;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TestStringConcatFactorySuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringConcatFactorySuite.class);

  private TestStringConcatFactorySuite() {}

  public static String plus(final String left, final String right) {
    LOGGER.debug("Before string plus {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus {}", result);
    return result;
  }

  public static String plusWithConstants(final String left, final String right) {
    LOGGER.debug("Before string plus {} {}", left, right);
    final String result = left + " " + right;
    LOGGER.debug("After string plus {}", result);
    return result;
  }

  public static String plusWithConstantsAndTags(final String left, final String right) {
    LOGGER.debug("Before string plus {} {}", left, right);
    final String result = "\u0001 " + left + " \u0002 " + right + ".";
    LOGGER.debug("After string plus {}", result);
    return result;
  }

  public static String plusWithUtfConstants(final String left, final String right) {
    LOGGER.debug("Before string plus {} {}", left, right);
    final String result = "𠆢" + left + "𠆢\u0001𠆢" + right + ".";
    LOGGER.debug("After string plus {}", result);
    return result;
  }

  public static String plus(
      final String left, final Object right, final String third, final Object fourth) {
    LOGGER.debug("Before string plus {} {} {} {}", left, right, third, fourth);
    final String result = left + right + third + fourth;
    LOGGER.debug("After string plus {}", result);
    return result;
  }

  public static String stringPlusWithMultipleObjects(final Object... target) {
    LOGGER.debug("Before string plus {}", Arrays.toString(target));
    String result = "";
    for (final Object item : target) {
      result += item;
    }
    LOGGER.debug("After string plus {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final int right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final byte right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final short right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final boolean right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final char right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final long right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final float right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }

  public static String stringPlusWithPrimitive(final String left, final double right) {
    LOGGER.debug("Before string plus with primitive {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string plus with primitive {}", result);
    return result;
  }
}
