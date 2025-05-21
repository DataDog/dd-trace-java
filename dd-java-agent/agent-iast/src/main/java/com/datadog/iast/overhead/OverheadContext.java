package com.datadog.iast.overhead;

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.NonBlockingSemaphore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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

  @Nullable final Map<String, Map<VulnerabilityType, Integer>> copyMap;
  @Nullable final Map<String, Map<VulnerabilityType, Integer>> requestMap;

  private final NonBlockingSemaphore availableVulnerabilities;
  private final boolean isGlobal;

  public OverheadContext(final int vulnerabilitiesPerRequest) {
    this(vulnerabilitiesPerRequest, false);
  }

  public OverheadContext(final int vulnerabilitiesPerRequest, final boolean isGlobal) {
    availableVulnerabilities =
        vulnerabilitiesPerRequest == UNLIMITED
            ? NonBlockingSemaphore.unlimited()
            : NonBlockingSemaphore.withPermitCount(vulnerabilitiesPerRequest);
    this.isGlobal = isGlobal;
    this.requestMap = isGlobal ? null : new HashMap<>();
    this.copyMap = isGlobal ? null : new HashMap<>();
  }

  public int getAvailableQuota() {
    return availableVulnerabilities.available();
  }

  public boolean consumeQuota(final int delta) {
    return availableVulnerabilities.acquire(delta);
  }

  public void reset() {
    availableVulnerabilities.reset();
  }

  public void resetMaps() {
    if (isGlobal || requestMap == null || copyMap == null) {
      return;
    }
    // If the budget is not consumed, we can reset the maps
    Set<String> keys = requestMap.keySet();
    if (getAvailableQuota() > 0) {
      keys.forEach(globalMap::remove);
      keys.clear();
      requestMap.clear();
      copyMap.clear();
      return;
    }
    keys.forEach(
        key -> {
          Map<VulnerabilityType, Integer> countMap = requestMap.get(key);
          if (countMap == null || countMap.isEmpty()) {
            globalMap.remove(key);
            return;
          }
          countMap.forEach(
              (key1, counter) -> {
                Map<VulnerabilityType, Integer> globalCountMap = globalMap.get(key);
                if (globalCountMap != null) {
                  Integer globalCounter = globalCountMap.getOrDefault(key1, 0);
                  if (counter > globalCounter) {
                    globalCountMap.put(key1, counter);
                  }
                } else {
                  globalCountMap = new HashMap<>();
                  globalCountMap.put(key1, counter);
                  globalMap.put(key, globalCountMap);
                }
              });
        });
    keys.clear();
    requestMap.clear();
    copyMap.clear();
  }

  public boolean isGlobal() {
    return isGlobal;
  }
}
