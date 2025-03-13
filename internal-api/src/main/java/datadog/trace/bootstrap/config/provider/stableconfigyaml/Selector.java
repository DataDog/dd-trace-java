package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.ArrayList;
import java.util.List;

public class Selector {
  private String origin;
  private String key;
  private List<String> matches;
  private String operator;

  public Selector() {
    this.origin = null;
    this.key = null;
    this.matches = new ArrayList<>();
    this.operator = null;
  }

  public Selector(String origin, String key, List<String> matches, String operator) {
    this.origin = origin;
    this.key = key;
    this.matches = matches;
    this.operator = operator;
  }

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
