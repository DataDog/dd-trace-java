package datadog.trace.instrumentation.azure.cosmos;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.json.JsonReader;
import datadog.trace.api.Pair;
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
    // -1 keeps trailing empty segments, so a trailing slash survives
    String[] parts = path.split("/", -1);
    int firstId = parts[0].isEmpty() ? 2 : 1;
    for (int i = firstId; i < parts.length; i += 2) {
      String key = parts[i - 1];
      if (parts[i].isEmpty()
          || DATABASES.equalsIgnoreCase(key)
          || COLLECTIONS.equalsIgnoreCase(key)) {
        continue;
      }
      parts[i] = "?";
    }
    return String.join("/", parts);
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
    String[] parts = trimmed.split("/", -1);
    if (parts.length < 2 || !DATABASES.equalsIgnoreCase(parts[0]) || parts[1].isEmpty()) {
      return Pair.of(null, null);
    }
    String container =
        parts.length >= 4 && COLLECTIONS.equalsIgnoreCase(parts[2]) && !parts[3].isEmpty()
            ? parts[3]
            : null;
    return Pair.of(parts[1], container);
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
