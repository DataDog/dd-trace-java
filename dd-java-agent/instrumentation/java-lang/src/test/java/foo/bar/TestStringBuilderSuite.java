package foo.bar;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestStringBuilderSuite implements TestAbstractStringBuilderSuite<StringBuilder> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringBuilderSuite.class);

  @Override
  public StringBuilder init(final String param) {
    LOGGER.debug("Before new string builder {}", param);
    final StringBuilder result = new StringBuilder(param);
    LOGGER.debug("After new string builder {}", result);
    return result;
  }

  @Override
  public StringBuilder init(final CharSequence param) {
    LOGGER.debug("Before new string builder {}", param);
    final StringBuilder result = new StringBuilder(param);
    LOGGER.debug("After new string builder {}", result);
    return result;
  }

  @Override
  public void append(final StringBuilder builder, final String param) {
    LOGGER.debug("Before string builder append {}", param);
    final StringBuilder result = builder.append(param);
    LOGGER.debug("After string builder append {}", result);
  }

  @Override
  public void append(final StringBuilder builder, final CharSequence param) {
    LOGGER.debug("Before string builder append {}", param);
    final StringBuilder result = builder.append(param);
    LOGGER.debug("After string builder append {}", result);
  }

  @Override
  public void append(final StringBuilder builder, final Object param) {
    LOGGER.debug("Before string builder append {}", param);
    final StringBuilder result = builder.append(param);
    LOGGER.debug("After string builder append {}", result);
  }

  @Override
  public String toString(final StringBuilder builder) {
    LOGGER.debug("Before string builder toString {}", builder);
    final String result = builder.toString();
    LOGGER.debug("After string builder toString {}", result);
    return result;
  }

  public String plus(final String left, final String right) {
    LOGGER.debug("Before string plus {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string builder toString {}", result);
    return result;
  }

  public String plus(final String left, final Object right) {
    LOGGER.debug("Before string plus {} {}", left, right);
    final String result = left + right;
    LOGGER.debug("After string builder toString {}", result);
    return result;
  }

  public String plus(final Object... items) {
    LOGGER.debug("Before string plus {}", Arrays.toString(items));
    String result = "";
    for (final Object item : items) {
      result += item;
    }
    LOGGER.debug("After string builder toString {}", result);
    return result;
  }
}
