package foo.bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestStringBufferSuite implements TestAbstractStringBuilderSuite<StringBuffer> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringBufferSuite.class);

  @Override
  public StringBuffer init(final String param) {
    LOGGER.debug("Before new string buffer {}", param);
    final StringBuffer result = new StringBuffer(param);
    LOGGER.debug("After new string buffer {}", result);
    return result;
  }

  @Override
  public StringBuffer init(final CharSequence param) {
    LOGGER.debug("Before new string buffer {}", param);
    final StringBuffer result = new StringBuffer(param);
    LOGGER.debug("After new string buffer {}", result);
    return result;
  }

  @Override
  public void append(final StringBuffer buffer, final String param) {
    LOGGER.debug("Before string buffer append {}", param);
    final StringBuffer result = buffer.append(param);
    LOGGER.debug("After string buffer append {}", result);
  }

  @Override
  public void append(final StringBuffer buffer, final CharSequence param) {
    LOGGER.debug("Before string buffer append {}", param);
    final StringBuffer result = buffer.append(param);
    LOGGER.debug("After string buffer append {}", result);
  }

  @Override
  public void append(final StringBuffer buffer, final Object param) {
    LOGGER.debug("Before string buffer append {}", param);
    final StringBuffer result = buffer.append(param);
    LOGGER.debug("After string buffer append {}", result);
  }

  @Override
  public String toString(final StringBuffer buffer) {
    LOGGER.debug("Before string buffer toString {}", buffer);
    final String result = buffer.toString();
    LOGGER.debug("After string buffer toString {}", result);
    return result;
  }
}
