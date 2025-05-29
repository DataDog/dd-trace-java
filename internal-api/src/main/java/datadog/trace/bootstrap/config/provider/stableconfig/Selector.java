package datadog.trace.bootstrap.config.provider.stableconfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class Selector {
  private final String origin;
  private final String key;
  private final List<String> matches;
  private final String operator;

  public Selector(String origin, String key, List<String> matches, String operator) {
    this.origin = origin;
    this.key = key;
    this.matches = matches;
    this.operator = operator;
  }

  public Selector(Object yaml) {
    Map map = (Map) yaml;
    origin = (String) map.get("origin");
    key = (String) map.get("key");
    List<String> rawMatches = (List<String>) map.get("matches");
    matches =
        rawMatches != null ? Collections.unmodifiableList(rawMatches) : Collections.emptyList();
    operator = (String) map.get("operator");
  }

  public String getOrigin() {
    return origin;
  }

  public String getKey() {
    return key;
  }

  public List<String> getMatches() {
    return matches;
  }

  public String getOperator() {
    return operator;
  }
}
