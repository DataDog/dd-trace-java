package datadog.trace.core.util;

import datadog.trace.core.CoreSpan;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TagsMatcher {

  public static TagsMatcher create(Map<String, String> tags) {
    Map<String, Matcher> matchers =
        tags.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> {
                      String tagValue = entry.getValue();
                      if (Matchers.isExact(tagValue)) {
                        return new Matchers.ExactMatcher(tagValue);
                      } else {
                        Pattern pattern = GlobPattern.globToRegexPattern(tagValue);
                        return new Matchers.PatternMatcher(pattern);
                      }
                    }));
    return new TagsMatcher(matchers);
  }

  private final Map<String, Matcher> matchers;

  public TagsMatcher(Map<String, Matcher> matchers) {
    this.matchers = matchers;
  }

  public <T extends CoreSpan<T>> boolean matches(T span) {
    return matchers.entrySet().stream()
        .allMatch(
            entry -> {
              String tagValue = span.getTag(entry.getKey());
              return tagValue != null && entry.getValue().matches(tagValue);
            });
  }
}
