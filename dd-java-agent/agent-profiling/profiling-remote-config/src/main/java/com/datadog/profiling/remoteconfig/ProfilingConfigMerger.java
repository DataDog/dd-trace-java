package com.datadog.profiling.remoteconfig;

import com.squareup.moshi.JsonReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges the raw {@code DEBUG} payloads into one set of config values. Each payload is a flat JSON
 * object of config keys to values, e.g. {@code {"profiling.ddprof.cpu.enabled": false}}.
 */
public final class ProfilingConfigMerger {
  private static final Logger log = LoggerFactory.getLogger(ProfilingConfigMerger.class);

  private ProfilingConfigMerger() {}

  /**
   * @param configsById raw JSON payloads keyed by config id
   * @return the merged values; payloads are applied in config id order, so for a key set by
   *     multiple payloads the one with the greatest config id wins. Invalid payloads are ignored.
   */
  public static Map<String, String> merge(Map<String, String> configsById) {
    Map<String, String> merged = new HashMap<>();
    for (String configId : new TreeSet<>(configsById.keySet())) {
      try {
        merged.putAll(parse(configsById.get(configId)));
      } catch (IOException | RuntimeException e) {
        log.warn("Ignoring invalid DEBUG config {}: {}", configId, e.toString());
      }
    }
    return merged;
  }

  static Map<String, String> parse(String json) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    JsonReader reader = JsonReader.of(new Buffer().writeUtf8(json));
    reader.beginObject();
    while (reader.hasNext()) {
      String key = reader.nextName();
      switch (reader.peek()) {
        case STRING:
        case NUMBER:
          // for numbers this returns the literal, e.g. "100" rather than "100.0"
          values.put(key, reader.nextString());
          break;
        case BOOLEAN:
          values.put(key, String.valueOf(reader.nextBoolean()));
          break;
        case BEGIN_ARRAY:
          values.put(key, readList(reader));
          break;
        default:
          // null or nested object: no override for this key
          reader.skipValue();
          break;
      }
    }
    reader.endObject();
    return values;
  }

  // lists are comma separated in the config, e.g. "a,b,c"
  private static String readList(JsonReader reader) throws IOException {
    StringBuilder list = new StringBuilder();
    reader.beginArray();
    while (reader.hasNext()) {
      String element;
      switch (reader.peek()) {
        case STRING:
        case NUMBER:
          element = reader.nextString();
          break;
        case BOOLEAN:
          element = String.valueOf(reader.nextBoolean());
          break;
        default:
          reader.skipValue();
          continue;
      }
      if (list.length() > 0) {
        list.append(',');
      }
      list.append(element);
    }
    reader.endArray();
    return list.toString();
  }
}
