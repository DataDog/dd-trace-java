package datadog.trace.bootstrap.config.provider.StableConfigYaml;

import java.util.List;

public class Selector {
  private String origin;
  private String key;
  private List<String> matches;
  private String operator;

  // Getters and setters
  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String origin) {
    this.origin = origin;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public List<String> getMatches() {
    return matches;
  }

  public void setMatches(List<String> matches) {
    this.matches = matches;
  }

  public String getOperator() {
    return operator;
  }

  public void setOperator(String operator) {
    this.operator = operator;
  }
}
