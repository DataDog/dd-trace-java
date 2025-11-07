package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TestStringJDK15Suite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringJDK15Suite.class);

  private TestStringJDK15Suite() {}

  public static String stringTranslateEscapes(String self) {
    LOGGER.debug("Before string translate escapes {}", self);
    final String result = self.translateEscapes();
    LOGGER.debug("After string translate escapes {}", result);
    return result;
  }
}
