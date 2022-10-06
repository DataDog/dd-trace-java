package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestStringSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringSuite.class);

  public static String concat(final String left, final String right) {
    LOGGER.debug("Before string concat {} {}", left, right);
    final String result = left.concat(right);
    LOGGER.debug("After string concat {}", result);
    return result;
  }

  public static String stringConstructor(CharSequence arg) {
    String result;

    LOGGER.debug("Before string constructor {} {}", arg, arg.getClass());
    if (arg.getClass() == String.class) {
      result = new String((String) arg);
    } else if (arg.getClass() == StringBuffer.class) {
      result = new String((StringBuffer) arg);
    } else if (arg.getClass() == StringBuilder.class) {
      result = new String((StringBuilder) arg);
    } else {
      throw new IllegalArgumentException();
    }
    LOGGER.debug("After string concat {}", result);
    return result;
  }
}
