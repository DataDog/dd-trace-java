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

  public static String stringTrim(final String self) {
    LOGGER.debug("Before string trim {} ", self);
    final String result = self.trim();
    LOGGER.debug("After string trim {}", result);
    return result;
  }
}
