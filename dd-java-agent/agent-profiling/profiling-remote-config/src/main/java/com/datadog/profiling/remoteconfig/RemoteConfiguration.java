package com.datadog.profiling.remoteconfig;

import static java.util.Collections.unmodifiableMap;

import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The profiling features that remote config can enable or disable, as set by the {@code profilers}
 * section of an {@code APM_PROFILING} payload, e.g. {@code
 * {"profilers":{"cpuTime":true,"wallTime":false}}}.
 *
 * <p>A feature the payload doesn't mention is not set: it takes the value of a lower priority
 * configuration, see {@link RemoteConfigurationStack}, or, if none sets it, the local one.
 */
public final class RemoteConfiguration {

  /** The features that can be set remotely, with their names in the payload. */
  public enum Feature {
    CPU_TIME("cpuTime"),
    WALL_TIME("wallTime"),
    /** Samples the live heap objects. */
    HEAP_OBJECTS("heapObjects"),
    /**
     * Tracks the size of the live heap. It is part of live heap profiling, so it samples the live
     * heap objects too, even when {@link #HEAP_OBJECTS} is disabled.
     */
    HEAP_SIZE("heapSize"),
    ALLOCATIONS("allocations");

    private final String jsonName;

    Feature(String jsonName) {
      this.jsonName = jsonName;
    }
  }

  /** A configuration that sets no feature. */
  public static final RemoteConfiguration EMPTY =
      new RemoteConfiguration(new EnumMap<>(Feature.class));

  /** Only the features that are set. */
  private final Map<Feature, Boolean> values;

  private RemoteConfiguration(Map<Feature, Boolean> values) {
    this.values = unmodifiableMap(values);
  }

  /** A configuration that sets the given features. */
  public static RemoteConfiguration of(Map<Feature, Boolean> values) {
    return values.isEmpty() ? EMPTY : new RemoteConfiguration(new EnumMap<>(values));
  }

  /**
   * Reads the {@code profilers} section of a payload, ignoring what is wrong with it: features that
   * are missing, unknown or not a boolean are not set. Unlike {@link #parse(Map, List)}, it can't
   * tell a payload that is not a profiling config from one that sets no feature: both give {@link
   * #EMPTY}.
   */
  @VisibleForTesting
  public static RemoteConfiguration parse(String json) {
    List<String> problems = new ArrayList<>();
    Map<String, Object> payload = ApmProfilingPayload.read(json, problems);
    RemoteConfiguration parsed = payload == null ? null : parse(payload, problems);
    return parsed == null ? EMPTY : parsed;
  }

  /**
   * Reads the {@code profilers} section of a payload, and reports what is wrong with it. Valid
   * features still apply when others are not.
   *
   * @param payload the payload, see {@link ApmProfilingPayload#read}
   * @param problems receives a description of each thing that is invalid
   * @return the configuration, or null when the payload is not a usable profiling config: it has no
   *     {@code profilers} section, as the shared {@code APM_PROFILING} product may carry others,
   *     the section is not an object, or it sets features but none of them validly, which are
   *     reported. Taking a malformed section for one that sets no feature would start an armed
   *     profiler with the local configuration, and would replace an older configuration of the same
   *     priority. An empty section is a configuration that sets no feature
   */
  static RemoteConfiguration parse(Map<String, Object> payload, List<String> problems) {
    if (!payload.containsKey("profilers")) {
      return null;
    }
    Object profilers = payload.get("profilers");
    if (!(profilers instanceof Map)) {
      problems.add("profilers is not an object");
      return null;
    }
    Map<?, ?> section = (Map<?, ?>) profilers;
    Map<Feature, Boolean> values = new EnumMap<>(Feature.class);
    boolean anyInvalid = false;
    for (Feature feature : Feature.values()) {
      if (!section.containsKey(feature.jsonName)) {
        continue;
      }
      Object value = section.get(feature.jsonName);
      if (value instanceof Boolean) {
        values.put(feature, (Boolean) value);
      } else {
        problems.add("profilers." + feature.jsonName + " is not a boolean");
        anyInvalid = true;
      }
    }
    if (anyInvalid && values.isEmpty()) {
      // nothing valid is left of what it sets
      return null;
    }
    return of(values);
  }

  /**
   * @return whether the feature is enabled or disabled, or null if this doesn't set it
   */
  public Boolean get(Feature feature) {
    return values.get(feature);
  }

  /**
   * @return whether this sets no feature
   */
  public boolean isEmpty() {
    return values.isEmpty();
  }

  /**
   * @return whether this sets all the remote configurable features, and disables them all
   */
  public boolean disablesEverything() {
    return values.size() == Feature.values().length && !values.containsValue(true);
  }

  /**
   * @param higher a configuration with a higher priority
   * @return a configuration where the features that {@code higher} sets overwrite the ones of this
   */
  RemoteConfiguration overriddenBy(RemoteConfiguration higher) {
    if (higher.isEmpty()) {
      return this;
    }
    if (isEmpty()) {
      return higher;
    }
    Map<Feature, Boolean> merged = new EnumMap<>(values);
    merged.putAll(higher.values);
    return new RemoteConfiguration(merged);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RemoteConfiguration && values.equals(((RemoteConfiguration) o).values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
