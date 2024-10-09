package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TestStringJDK17Suite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringJDK17Suite.class);

  private TestStringJDK17Suite() {}

  public static String stringIndent(String self, int indentation) {
    LOGGER.debug("Before string indent {} indentation", indentation);
    final String result = self.indent(indentation);
    LOGGER.debug("After string indent {}", result);
    return result;
  }
}
