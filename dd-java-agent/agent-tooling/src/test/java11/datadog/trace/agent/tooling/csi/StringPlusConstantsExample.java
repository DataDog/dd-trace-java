package datadog.trace.agent.tooling.csi;

import datadog.trace.api.function.TriFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringPlusConstantsExample implements TriFunction<String, String, String, String> {

  private static final Logger LOGGER = LoggerFactory.getLogger(StringPlusConstantsExample.class);

  public String apply(final String first, final String second, final String third) {
    LOGGER.debug("Before apply");
    final String result = first + " " + second + " " + third;
    LOGGER.debug("After apply {}", result);
    return result;
  }
}
