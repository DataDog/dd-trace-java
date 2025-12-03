package com.datadog.iast.overhead;

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.util.NonBlockingSemaphore;
import datadog.trace.api.iast.VulnerabilityTypes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OverheadContext {

  /** Maximum number of distinct endpoints to remember in the global cache. */
  private static final int GLOBAL_MAP_MAX_SIZE = 4096;

  /**
   * Global concurrent cache mapping each “method + path” key to its historical vulnerabilityCounts
   * map. As soon as size() > GLOBAL_MAP_MAX_SIZE, we clear() the whole map.
   */
  static final ConcurrentMap<String, AtomicIntegerArray> globalMap =
      new ConcurrentHashMap<String, AtomicIntegerArray>() {

        @Override
        public AtomicIntegerArray computeIfAbsent(
            String key,
            @Nonnull Function<? super String, ? extends AtomicIntegerArray> mappingFunction) {
          if (this.size() >= GLOBAL_MAP_MAX_SIZE) {
            super.clear();
          }
          return super.computeIfAbsent(key, mappingFunction);
        }
      };

  // Snapshot of the globalMap for the current request
  private @Nullable final Map<String, int[]> copyMap;
  // Map of vulnerabilities per endpoint for the current request, needs to use AtomicIntegerArray
  // because it's possible to have concurrent updates in the same request
  private @Nullable final Map<String, AtomicIntegerArray> requestMap;

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
    this.requestMap = isGlobal ? null : new ConcurrentHashMap<>();
    this.copyMap = isGlobal ? null : new ConcurrentHashMap<>();
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
    // If this is a global context, we do not reset the maps
    if (isGlobal || requestMap == null || copyMap == null) {
      return;
    }
    Set<String> endpoints = requestMap.keySet();
    // If the budget is not consumed, we can reset the maps
    if (getAvailableQuota() > 0) {
      // clean endpoints from globalMap
      endpoints.forEach(globalMap::remove);
      return;
    }
    // If the budget is consumed, we need to merge the requestMap into the globalMap
    endpoints.forEach(
        endpoint -> {
          AtomicIntegerArray countMap = requestMap.get(endpoint);
          // should not happen, but just in case
          if (countMap == null) {
            globalMap.remove(endpoint);
            return;
          }
          // Iterate over the vulnerabilities and update the globalMap
          int numberOfVulnerabilities = VulnerabilityTypes.STRINGS.length;
          for (int i = 0; i < numberOfVulnerabilities; i++) {
            int counter = countMap.get(i);
            if (counter > 0) {
              AtomicIntegerArray globalCountMap =
                  globalMap.computeIfAbsent(
                      endpoint, value -> new AtomicIntegerArray(numberOfVulnerabilities));

              globalCountMap.accumulateAndGet(i, counter, Math::max);
            }
          }
        });
  }

  public boolean isGlobal() {
    return isGlobal;
  }

  public @Nullable Map<String, int[]> getCopyMap() {
    return copyMap;
  }

  public @Nullable Map<String, AtomicIntegerArray> getRequestMap() {
    return requestMap;
  }
}
