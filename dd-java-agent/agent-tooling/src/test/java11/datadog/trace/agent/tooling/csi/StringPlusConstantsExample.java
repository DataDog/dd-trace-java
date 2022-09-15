package datadog.trace.agent.tooling.csi;

import datadog.trace.api.function.TriFunction;

public class StringPlusConstantsExample implements TriFunction<String, String, String, String> {

  public String apply(final String first, final String second, final String third) {
    return first + " " + second + " " + third;
  }
}
