package com.datadog.iast.overhead;

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.NonBlockingSemaphore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class OverheadContext {

  /**
   * Maximum number of distinct endpoints to remember in the global cache (LRU eviction beyond this
   * size).
   */
  private static final int GLOBAL_MAP_MAX_SIZE = 4096;

  /**
   * Global LRU cache mapping each “method + path” key to its historical vulnerabilityCounts map.
   * Key: HTTP_METHOD + " " + HTTP_PATH Value: Map<vulnerabilityType, count>
   */
  static final Map<String, Map<VulnerabilityType, Integer>> globalMap =
      new LinkedHashMap<String, Map<VulnerabilityType, Integer>>(GLOBAL_MAP_MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(
            Map.Entry<String, Map<VulnerabilityType, Integer>> eldest) {
          return size() > GLOBAL_MAP_MAX_SIZE;
        }
      };

  Map<String, Map<VulnerabilityType, Integer>> copyMap;
  final Map<String, Map<VulnerabilityType, Integer>> requestMap = new HashMap<>();
  final Set<String> keys = new HashSet<>();

  private final NonBlockingSemaphore availableVulnerabilities;

  public OverheadContext(final int vulnerabilitiesPerRequest) {
    availableVulnerabilities =
        vulnerabilitiesPerRequest == UNLIMITED
            ? NonBlockingSemaphore.unlimited()
            : NonBlockingSemaphore.withPermitCount(vulnerabilitiesPerRequest);
  }

  public int getAvailableQuota() {
    return availableVulnerabilities.available();
  }

  public boolean consumeQuota(final int delta) {
    return availableVulnerabilities.acquire(delta);
  }

  public void reset() {
    resetMaps();
    availableVulnerabilities.reset();
  }

  public void resetMaps() {
    // If the budget is not consumed, we can reset the maps
    if (getAvailableQuota() > 0) {
      keys.forEach(globalMap::remove);
      return;
    }
    keys.forEach(
        key -> {
          requestMap
              .get(key)
              .forEach(
                  (key1, counter) -> {
                    Integer globalCounter = globalMap.get(key).getOrDefault(key1, 0);
                    if (counter > globalCounter) {
                      globalMap.get(key).put(key1, counter);
                    }
                  });
        });
    keys.clear();
    requestMap.clear();
    copyMap.clear();
  }
}
