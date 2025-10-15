package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TestStringJDK11Suite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringJDK11Suite.class);

  private TestStringJDK11Suite() {}

  public static String stringRepeat(String self, int count) {
    LOGGER.debug("Before string repeat {} times", count);
    final String result = self.repeat(count);
    LOGGER.debug("After string repeat {}", result);
    return result;
  }

  public static String stringStrip(final String self) {
    LOGGER.debug("Before string strip {}", self);
    final String result = self.strip();
    LOGGER.debug("After string strip {}", result);
    return result;
  }

  public static String stringStripLeading(final String self) {
    LOGGER.debug("Before string stripLeading {}", self);
    final String result = self.stripLeading();
    LOGGER.debug("After string stripLeading {}", result);
    return result;
  }

  public static String stringStripTrailing(final String self) {
    LOGGER.debug("Before string stripTrailing {}", self);
    final String result = self.stripTrailing();
    LOGGER.debug("After string stripTrailing {}", result);
    return result;
  }
}
