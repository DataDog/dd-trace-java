package datadog.trace.agent.tooling.csi;

import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringConcatCategory2Example implements BiFunction<String, String, String> {

  private static final Logger LOGGER = LoggerFactory.getLogger(StringConcatCategory2Example.class);

  public String apply(final String first, final String second) {
    LOGGER.debug("Before apply : {}", System.currentTimeMillis() / 1000L);
    final String result = first.concat(second);
    LOGGER.debug("After apply : {}", System.currentTimeMillis() / 1000L);
    return result;
  }
}
