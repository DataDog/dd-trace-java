package com.datadog.profiling.remoteconfig;

import com.squareup.moshi.JsonReader;
import java.io.IOException;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a {@code DEBUG} payload is intended for this application, based on its {@code
 * targets} list, e.g. {@code {"targets":[{"env":"staging","service":"my-app"},{"service":"x"}]}}.
 *
 * <p>A payload matches when any of its targets matches. Within a target, a missing {@code env} or
 * {@code service}, or the value {@code "*"}, matches anything. Payloads without a {@code targets}
 * list are not profiling configs (the {@code DEBUG} product is shared) and never match.
 */
public final class ProfilingConfigTargets {
  private static final Logger log = LoggerFactory.getLogger(ProfilingConfigTargets.class);

  private static final String WILDCARD = "*";

  private ProfilingConfigTargets() {}

  public static boolean matches(String json, String env, String service) {
    try {
      return matchesTargets(JsonReader.of(new Buffer().writeUtf8(json)), env, service);
    } catch (IOException | RuntimeException e) {
      log.debug("Ignoring DEBUG config with unreadable targets: {}", e.toString());
      return false;
    }
  }

  private static boolean matchesTargets(JsonReader reader, String env, String service)
      throws IOException {
    reader.beginObject();
    while (reader.hasNext()) {
      if (!"targets".equals(reader.nextName()) || reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
        reader.skipValue();
        continue;
      }
      boolean matched = false;
      reader.beginArray();
      while (reader.hasNext()) {
        // keep reading after a match so the reader stays consistent
        matched |= matchesTarget(reader, env, service);
      }
      reader.endArray();
      return matched;
    }
    return false;
  }

  private static boolean matchesTarget(JsonReader reader, String env, String service)
      throws IOException {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue();
      return false;
    }
    boolean matched = true;
    reader.beginObject();
    while (reader.hasNext()) {
      String name = reader.nextName();
      if (("env".equals(name) || "service".equals(name))
          && reader.peek() == JsonReader.Token.STRING) {
        String expected = reader.nextString();
        String actual = "env".equals(name) ? env : service;
        matched &= WILDCARD.equals(expected) || expected.equals(actual);
      } else {
        reader.skipValue();
      }
    }
    reader.endObject();
    return matched;
  }
}
