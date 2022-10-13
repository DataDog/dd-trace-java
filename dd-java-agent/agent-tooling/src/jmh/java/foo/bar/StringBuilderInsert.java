package foo.bar;

import datadog.trace.api.function.Function;

public class StringBuilderInsert implements Function<String, String> {

  @Override
  public String apply(final String value) {
    final StringBuilder builder = new StringBuilder();
    builder.insert(builder.length(), value.toCharArray());
    return builder.toString();
  }
}
