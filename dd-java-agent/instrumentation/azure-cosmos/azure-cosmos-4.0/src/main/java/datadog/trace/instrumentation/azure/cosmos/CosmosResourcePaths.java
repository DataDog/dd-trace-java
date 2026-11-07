package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.util.Strings.split;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.json.JsonReader;
import datadog.trace.api.Pair;
import datadog.trace.util.SubSequence;
import java.io.IOException;

/**
 * Derives span resource and tags from a Cosmos resource path such as {@code
 * /dbs/myDb/colls/myColl/docs/item1}. Ported from the dd-trace-py Cosmos integration so both
 * tracers produce the same resources.
 */
public final class CosmosResourcePaths {
  private static final String DATABASES = "dbs";
  private static final String COLLECTIONS = "colls";
  private static final String ID = "id";

  private CosmosResourcePaths() {}

  /**
   * Keeps the ids that follow {@code dbs} and {@code colls} and replaces every other id segment
   * with {@code ?}. Segments alternate {@code key/id}; empty segments are kept as they are.
   */
  public static String normalize(String path) {
    if (path == null || path.isEmpty()) {
      return path;
    }
    StringBuilder normalized = new StringBuilder(path.length());
    // with a leading slash the first segment is empty, so the first id comes one segment later
    int firstId = path.charAt(0) == '/' ? 2 : 1;
    int index = 0;
    SubSequence key = null;
    for (SubSequence segment : split(path, '/')) {
      if (index > 0) {
        normalized.append('/');
      }
      boolean isId = index >= firstId && (index - firstId) % 2 == 0;
      if (isId
          && segment.length() > 0
          && !key.equalsIgnoreCase(DATABASES)
          && !key.equalsIgnoreCase(COLLECTIONS)) {
        normalized.append('?');
      } else {
        segment.appendTo(normalized);
      }
      key = segment;
      index++;
    }
    return normalized.toString();
  }

  /**
   * Returns the database id (left) and the container id (right) addressed by a path. Each of them
   * is null when the path does not name one.
   */
  public static Pair<String, String> databaseAndContainer(String path) {
    if (path == null || path.isEmpty()) {
      return Pair.of(null, null);
    }
    String trimmed = path.length() > 1 && path.charAt(0) == '/' ? path.substring(1) : path;
    // only dbs/<database>/colls/<container> matters
    SubSequence[] parts = new SubSequence[4];
    int count = 0;
    for (SubSequence segment : split(trimmed, '/')) {
      parts[count++] = segment;
      if (count == parts.length) {
        break;
      }
    }
    if (count < 2 || !parts[0].equalsIgnoreCase(DATABASES) || parts[1].length() == 0) {
      return Pair.of(null, null);
    }
    String container =
        count == 4 && parts[2].equalsIgnoreCase(COLLECTIONS) && parts[3].length() > 0
            ? parts[3].toString()
            : null;
    return Pair.of(parts[1].toString(), container);
  }

  /**
   * Reads the top-level {@code id} string from a JSON request body. Only used for database
   * creation, whose path ({@code /dbs}) does not carry the new database id.
   */
  public static String idFromBody(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try (JsonReader reader = new JsonReader(new String(body, UTF_8))) {
      reader.beginObject();
      String name;
      while ((name = reader.nextName()) != null) {
        if (ID.equals(name)) {
          return reader.nextString();
        }
        // skips the value, including nested objects that could hold another id
        reader.nextValue();
      }
    } catch (IOException e) {
      // not a JSON object, or the id is not a string
    }
    return null;
  }
}
