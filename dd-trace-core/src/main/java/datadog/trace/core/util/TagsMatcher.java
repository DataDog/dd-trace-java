package datadog.trace.core.util;

import datadog.trace.core.CoreSpan;
import java.util.HashMap;
import java.util.Map;

public final class TagsMatcher {
  public static TagsMatcher create(Map<String, String> tags) {
    Map<String, Matcher> matchers = new HashMap<String, Matcher>(tags.size());

    for (Map.Entry<String, String> entry : tags.entrySet()) {
      String tag = entry.getKey();
      String glob = entry.getValue();

      matchers.put(tag, Matchers.compileGlob(glob));
    }
    return new TagsMatcher(matchers);
  }

  private final Map<String, Matcher> matchers;

  public TagsMatcher(Map<String, Matcher> matchers) {
    this.matchers = matchers;
  }

  public <T extends CoreSpan<T>> boolean matches(T span) {
    for (Map.Entry<String, Matcher> entry : matchers.entrySet()) {
      String tag = entry.getKey();
      Matcher matcher = entry.getValue();

      Object value = span.getTag(tag);
      if (value == null || !matcher.matches(value)) return false;
    }
    return true;
  }
}
