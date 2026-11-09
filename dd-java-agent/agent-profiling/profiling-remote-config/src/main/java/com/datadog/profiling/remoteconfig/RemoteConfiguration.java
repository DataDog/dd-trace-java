package com.datadog.profiling.remoteconfig;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static java.util.Collections.unmodifiableMap;

import com.squareup.moshi.JsonReader;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The profiling features that remote config can enable or disable, as set by the {@code profilers}
 * section of an {@code APM_PROFILING} payload, e.g. {@code
 * {"profilers":{"cpuTime":true,"wallTime":false}}}.
 *
 * <p>A feature the payload doesn't mention is not set: it takes the value of a lower priority
 * configuration, see {@link RemoteConfigurationStack}, or, if none sets it, the local one.
 */
public final class RemoteConfiguration {
  private static final Logger log = LoggerFactory.getLogger(RemoteConfiguration.class);

  /** The features that can be set remotely, with their names in the payload. */
  public enum Feature {
    CPU_TIME("cpuTime"),
    WALL_TIME("wallTime"),
    HEAP_OBJECTS("heapObjects"),
    ALLOCATIONS("allocations");

    private final String jsonName;

    Feature(String jsonName) {
      this.jsonName = jsonName;
    }

    public String jsonName() {
      return jsonName;
    }

    static Feature fromJsonName(String name) {
      for (Feature feature : values()) {
        if (feature.jsonName.equals(name)) {
          return feature;
        }
      }
      return null;
    }
  }

  /** A configuration that sets no feature. */
  public static final RemoteConfiguration EMPTY =
      new RemoteConfiguration(new EnumMap<>(Feature.class));

  // only the features that are set
  private final Map<Feature, Boolean> values;

  private RemoteConfiguration(Map<Feature, Boolean> values) {
    this.values = unmodifiableMap(values);
  }

  /** A configuration that sets the given features. */
  public static RemoteConfiguration of(Map<Feature, Boolean> values) {
    return values.isEmpty() ? EMPTY : new RemoteConfiguration(new EnumMap<>(values));
  }

  /**
   * Reads the {@code profilers} section of a payload. Features that are missing, unknown or not a
   * boolean are not set, so a payload without a {@code profilers} section, or one that can't be
   * read, gives {@link #EMPTY}.
   */
  public static RemoteConfiguration parse(String json) {
    try {
      return readPayload(JsonReader.of(new Buffer().writeUtf8(json)));
    } catch (IOException | RuntimeException e) {
      log.debug("Ignoring the profilers of an unreadable APM_PROFILING config: {}", e.toString());
      return EMPTY;
    }
  }

  private static RemoteConfiguration readPayload(JsonReader reader) throws IOException {
    reader.beginObject();
    while (reader.hasNext()) {
      if ("profilers".equals(reader.nextName()) && reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
        return readProfilers(reader);
      }
      reader.skipValue();
    }
    return EMPTY;
  }

  private static RemoteConfiguration readProfilers(JsonReader reader) throws IOException {
    Map<Feature, Boolean> values = new EnumMap<>(Feature.class);
    reader.beginObject();
    while (reader.hasNext()) {
      String name = reader.nextName();
      Feature feature = Feature.fromJsonName(name);
      if (feature != null && reader.peek() == JsonReader.Token.BOOLEAN) {
        values.put(feature, reader.nextBoolean());
      } else {
        if (feature != null) {
          log.debug("Ignoring profilers.{}: not a boolean", name);
        }
        reader.skipValue();
      }
    }
    reader.endObject();
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
   * @param higher a configuration with a higher priority
   * @return a configuration where the features that {@code higher} sets overwrite the ones of this
   */
  public RemoteConfiguration overriddenBy(RemoteConfiguration higher) {
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

  /**
   * The profiler configuration that sets the features this sets, and nothing else, so that the
   * others keep their local value:
   *
   * <ul>
   *   <li>{@code cpuTime}, {@code wallTime} and {@code allocations} enable the Datadog profiler's
   *       CPU, wall time and allocation profiling;
   *   <li>{@code heapObjects} enables the live heap (object sampling) profiler.
   * </ul>
   *
   * @return the configuration values by config name
   */
  public Map<String, String> toConfigValues() {
    Map<String, String> config = new HashMap<>();
    put(config, PROFILING_DATADOG_PROFILER_CPU_ENABLED, get(Feature.CPU_TIME));
    put(config, PROFILING_DATADOG_PROFILER_WALL_ENABLED, get(Feature.WALL_TIME));
    put(config, PROFILING_ALLOCATION_ENABLED, get(Feature.ALLOCATIONS));
    Boolean heapObjects = get(Feature.HEAP_OBJECTS);
    put(config, PROFILING_HEAP_ENABLED, heapObjects);
    put(config, PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, heapObjects);
    return config;
  }

  private static void put(Map<String, String> config, String key, Boolean value) {
    if (value != null) {
      config.put(key, value.toString());
    }
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
