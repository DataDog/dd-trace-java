package datadog.trace.api.featureflag.ufc.v1;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class FlagMap extends HashMap<String, Flag> {
  private final Set<String> rejected = new HashSet<>();

  public void reject(final String key) {
    rejected.add(key);
  }

  public boolean isRejected(final String key) {
    return rejected.contains(key);
  }
}
