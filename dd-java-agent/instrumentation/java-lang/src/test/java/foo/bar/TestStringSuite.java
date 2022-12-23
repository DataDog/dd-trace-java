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

  public static String substring(String self, int beginIndex, int endIndex) {
    LOGGER.debug("Before string substring {} from {} to {}", self, beginIndex, endIndex);
    final String result = self.substring(beginIndex, endIndex);
    LOGGER.debug("After string substring {}", result);
    return result;
  }

  public static String substring(String self, int beginIndex) {
    LOGGER.debug("Before string substring {} from {}", self, beginIndex);
    final String result = self.substring(beginIndex);
    LOGGER.debug("After string substring {}", result);
    return result;
  }

  public static CharSequence subSequence(String self, Integer beginIndex, Integer endIndex) {
    LOGGER.debug("Before string subSequence {} from {} to {}", self, beginIndex, endIndex);
    final CharSequence result = self.subSequence(beginIndex, endIndex);
    LOGGER.debug("After string subSequence {}", result);
    return result;
  }

  public static String join(CharSequence delimiter, CharSequence[] elements) {
    LOGGER.debug("Before string join {} with {}", elements, delimiter);
    final String result = String.join(delimiter, elements);
    LOGGER.debug("After string join {}", result);
    return result;
  }

  public static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
    LOGGER.debug("Before string join {} with {}", elements, delimiter);
    final String result = String.join(delimiter, elements);
    LOGGER.debug("After string join {}", result);
    return result;
  }
}
