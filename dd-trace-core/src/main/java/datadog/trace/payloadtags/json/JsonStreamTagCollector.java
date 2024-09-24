package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import okio.BufferedSource;
import okio.Okio;

public class JsonStreamTagCollector {

  public static final String REDACTED = "redacted";
  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  public static Map<String, Object> collectTagsFromJson(
      InputStream is,
      Function<JsonPath, Boolean> redactionFilter,
      String tagPrefix,
      int tagLimit,
      int depthLimit)
      throws IOException {
    Map<String, Object> tags = new LinkedHashMap<>();
    JsonPath.Builder path = JsonPath.Builder.start();
    StringBuilder pathPrefix = new StringBuilder(tagPrefix);
    return collectTagsFromJson(is, redactionFilter, pathPrefix, tagLimit, depthLimit, path, tags);
  }

  private static Map<String, Object> collectTagsFromJson(
      InputStream is,
      Function<JsonPath, Boolean> redactionFilter,
      StringBuilder tagPrefix,
      int tagLimit,
      int depthLimit,
      JsonPath.Builder path,
      Map<String, Object> tags)
      throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(is))) {
      try (JsonReader reader = JsonReader.of(source)) {
        reader.setLenient(true);
        if (!collectTagsFromJson(
            reader, redactionFilter, tagPrefix, tagLimit, depthLimit, path, tags)) {
          tags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
        }
        return tags;
      }
    }
  }

  /** @return - true when scanned all document, false when reached tag limit */
  private static boolean collectTagsFromJson(
      JsonReader reader,
      Function<JsonPath, Boolean> redactionFilter,
      StringBuilder tagPrefix,
      int tagLimit,
      int depthLimit,
      JsonPath.Builder path,
      Map<String, Object> tags)
      throws IOException {

    while (tagLimit > 0) {

      switch (reader.peek()) {
        case END_DOCUMENT:
          return true;

        case BEGIN_ARRAY:
          if (redactValueIfNeeded(reader, redactionFilter, path, tags, tagPrefix)) {
            path.endValue();
          } else if (depthLimit > 0) {
            reader.beginArray();
            path.beginArray();
            depthLimit--;
          } else {
            path.endValue();
            reader.skipValue();
          }
          break;

        case BEGIN_OBJECT:
          if (redactValueIfNeeded(reader, redactionFilter, path, tags, tagPrefix)) {
            path.endValue();
          } else if (depthLimit > 0) {
            reader.beginObject();
            depthLimit--;
          } else {
            path.endValue();
            reader.skipValue();
          }
          break;

        case NAME:
          String key = reader.nextName();
          path.key(key);
          break;

        case END_ARRAY:
          reader.endArray();
          path.endArray();
          path.endValue();
          depthLimit++;
          break;

        case END_OBJECT:
          reader.endObject();
          path.endValue();
          depthLimit++;
          break;

        case BOOLEAN:
          if (!redactValueIfNeeded(reader, redactionFilter, path, tags, tagPrefix)) {
            tags.put(path.dotted(tagPrefix), reader.nextBoolean());
          }
          path.endValue();
          break;

        case STRING:
          if (!redactValueIfNeeded(reader, redactionFilter, path, tags, tagPrefix)) {
            String raw = reader.nextString();
            boolean looksLikeJson =
                raw.startsWith("{") && raw.endsWith("}")
                    || raw.startsWith("[") && raw.endsWith("]");
            if (!looksLikeJson) {
              tags.put(path.dotted(tagPrefix), raw);
              tagLimit--;
            } else if (depthLimit > 0) {
              try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
                Map<String, Object> innerTags = new LinkedHashMap<>();
                JsonPath.Builder innerPath = path.copy();
                collectTagsFromJson(
                    is, redactionFilter, tagPrefix, tagLimit, depthLimit, innerPath, innerTags);
                tags.putAll(innerTags);
                tagLimit -= innerTags.size();
              } catch (Exception e) {
                // TODO maybe debug log?
                tags.put(path.dotted(tagPrefix), raw);
                tagLimit--;
              }
            }
          }
          path.endValue();
          break;

        case NUMBER:
          if (!redactValueIfNeeded(reader, redactionFilter, path, tags, tagPrefix)) {
            // convert number to a string to preserve exact format
            tags.put(path.dotted(tagPrefix), reader.nextString());
            tagLimit--;
          }
          path.endValue();
          break;

        case NULL:
          if (!redactValueIfNeeded(reader, redactionFilter, path, tags, tagPrefix)) {
            reader.nextNull();
            // convert `null` to a string, otherwise it won't be set as a tag value
            tags.put(path.dotted(tagPrefix), "null");
            tagLimit--;
          }
          path.endValue();
          break;
      }
    }
    return false;
  }

  private static boolean redactValueIfNeeded(
      JsonReader reader,
      Function<JsonPath, Boolean> redactionFilter,
      JsonPath.Builder path,
      Map<String, Object> tags,
      StringBuilder tagPrefix)
      throws IOException {
    if (redactionFilter.apply(path.jsonPath())) {
      reader.skipValue();
      tags.put(path.dotted(tagPrefix), REDACTED);
      return true;
    }
    return false;
  }
}
