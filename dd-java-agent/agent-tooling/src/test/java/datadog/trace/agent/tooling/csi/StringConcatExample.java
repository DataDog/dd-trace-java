package datadog.trace.agent.tooling.csi;

import datadog.trace.api.function.BiFunction;

public class StringConcatExample implements BiFunction<String, String, String> {

  public String apply(final String first, final String second) {
    return first.concat(second);
  }
}
