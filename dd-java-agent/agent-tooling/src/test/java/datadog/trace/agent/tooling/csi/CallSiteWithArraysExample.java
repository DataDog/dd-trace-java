package datadog.trace.agent.tooling.csi;

import datadog.trace.api.function.TriFunction;

public class CallSiteWithArraysExample implements TriFunction<String, Integer, Integer, String> {

  @Override
  public String apply(final String text, final Integer offset, final Integer length) {
    return new StringBuilder().insert(0, text.toCharArray(), offset, length).toString();
  }
}
