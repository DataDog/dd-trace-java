package datadog.trace.agent.tooling.csi;

import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringConcatExample implements BiFunction<String, String, String> {

  private static final Logger LOGGER = LoggerFactory.getLogger(StringConcatExample.class);

  public String apply(final String first, final String second) {
    LOGGER.debug("Before apply");
    final String result = first.concat(second);
    LOGGER.debug("After apply {}", result);
    return result;
  }
}
