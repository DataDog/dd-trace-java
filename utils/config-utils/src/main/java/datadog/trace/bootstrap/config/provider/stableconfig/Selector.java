package datadog.trace.bootstrap.config.provider.stableconfig;

import static datadog.trace.bootstrap.config.provider.stableconfig.StableConfigMappingException.throwStableConfigMappingException;

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

  public static Selector from(Map<?, ?> map) {
    Object originObj = map.get("origin");
    if (originObj == null) {
      throwStableConfigMappingException("Missing 'origin' in selector:", map);
    }
    if (!(originObj instanceof String)) {
      throwStableConfigMappingException(
          "'origin' must be a string, but got: " + originObj.getClass().getSimpleName() + ": ",
          originObj);
    }
    String origin = (String) originObj;

    Object keyObj = map.get("key");
    String key = (keyObj instanceof String) ? (String) keyObj : null;

    Object matchesObj = map.get("matches");
    if (matchesObj != null && !(matchesObj instanceof List)) {
      throwStableConfigMappingException(
          "'matches' must be a list, but got: " + matchesObj.getClass().getSimpleName() + ": ",
          matchesObj);
    }
    List<String> rawMatches = (List<String>) matchesObj;
    List<String> matches =
        rawMatches != null ? Collections.unmodifiableList(rawMatches) : Collections.emptyList();

    Object operatorObj = map.get("operator");
    if (operatorObj == null) {
      throwStableConfigMappingException("Missing 'operator' in selector:", map);
    }
    if (!(operatorObj instanceof String)) {
      throwStableConfigMappingException(
          "'operator' must be a string, but got: " + operatorObj.getClass().getSimpleName() + ": ",
          operatorObj);
    }
    String operator = (String) operatorObj;

    return new Selector(origin, key, matches, operator);
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
