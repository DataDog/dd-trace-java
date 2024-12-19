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
  public void append(final StringBuffer builder, final CharSequence param, int start, int end) {
    LOGGER.debug("Before string buffer append {} with start {} and end {}", param, start, end);
    final StringBuffer result = builder.append(param, start, end);
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

  @Override
  public String substring(final StringBuffer self, final int beginIndex, final int endIndex) {
    LOGGER.debug("Before string buffer substring {} from {} to {}", self, beginIndex, endIndex);
    final String result = self.substring(beginIndex, endIndex);
    LOGGER.debug("After string buffer substring {}", result);
    return result;
  }

  @Override
  public String substring(final StringBuffer self, final int beginIndex) {
    LOGGER.debug("Before string buffer substring {} from {}", self, beginIndex);
    final String result = self.substring(beginIndex);
    LOGGER.debug("After string buffer substring {}", result);
    return result;
  }

  @Override
  public CharSequence subSequence(
      final StringBuffer self, final int beginIndex, final int endIndex) {
    LOGGER.debug("Before string buffer subSequence {} from {} to {}", self, beginIndex, endIndex);
    final CharSequence result = self.subSequence(beginIndex, endIndex);
    LOGGER.debug("After string buffer subSequence {}", result);
    return result;
  }

  @Override
  public void setLength(final StringBuffer self, final int length) {
    LOGGER.debug("Before string buffer setLength {} with length {}", self, length);
    self.setLength(length);
    LOGGER.debug("After string buffer setLength {}", self);
  }
}
