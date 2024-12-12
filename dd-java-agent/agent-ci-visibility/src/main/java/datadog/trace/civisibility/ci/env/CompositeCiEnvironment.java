package datadog.trace.civisibility.ci.env;

import java.util.HashMap;
import java.util.Map;

public class CompositeCiEnvironment implements CiEnvironment {

  private final CiEnvironment[] delegates;

  public CompositeCiEnvironment(CiEnvironment... delegates) {
    this.delegates = delegates;
  }

  @Override
  public String get(String name) {
    for (CiEnvironment delegate : delegates) {
      String value = delegate.get(name);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  @Override
  public Map<String, String> get() {
    Map<String, String> combinedEnvironment = new HashMap<>();
    for (int i = delegates.length - 1; i >= 0; i--) {
      // iterating over delegates in reverse order,
      // since delegates with lower indices have higher priority
      combinedEnvironment.putAll(delegates[i].get());
    }
    return combinedEnvironment;
  }
}
