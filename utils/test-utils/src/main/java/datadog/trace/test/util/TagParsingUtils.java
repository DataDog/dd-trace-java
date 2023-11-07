package datadog.trace.test.util;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public final class TagParsingUtils {
  @SuppressForbidden
  public static Map<String, String> parseTags(final Collection<Object> params) {
    return params.stream()
        .map(p -> ((String) p).split(":", 2))
        .collect(Collectors.toMap(p -> p[0], p -> p[1]));
  }
}
