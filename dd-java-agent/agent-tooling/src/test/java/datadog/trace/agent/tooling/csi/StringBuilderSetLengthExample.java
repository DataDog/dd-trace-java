package datadog.trace.agent.tooling.csi;

import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringBuilderSetLengthExample implements BiConsumer<StringBuilder, Integer> {

  private static final Logger LOGGER = LoggerFactory.getLogger(StringBuilderSetLengthExample.class);

  public void accept(final StringBuilder builder, final Integer length) {
    LOGGER.debug("Before apply {} {}", builder, length);
    builder.setLength(length);
    LOGGER.debug("After apply {} {}", builder, length);
  }
}
