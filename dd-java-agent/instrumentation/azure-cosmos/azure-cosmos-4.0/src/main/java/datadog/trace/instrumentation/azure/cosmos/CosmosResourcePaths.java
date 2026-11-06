package datadog.trace.instrumentation.azure.cosmos;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Derives span resource and tags from a Cosmos resource path such as {@code
 * /dbs/myDb/colls/myColl/docs/item1}. Ported from the dd-trace-py Cosmos integration so both
 * tracers produce the same resources.
 */
public final class CosmosResourcePaths {
  private static final String DATABASES = "dbs";
  private static final String COLLECTIONS = "colls";
  private static final String ID_KEY = "\"id\"";

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
   * Returns the database id and the container id addressed by a path, each {@code null} when the
   * path does not name one.
   */
  public static String[] databaseAndContainer(String path) {
    String[] result = new String[2];
    if (path == null || path.isEmpty()) {
      return result;
    }
    String trimmed = path.length() > 1 && path.charAt(0) == '/' ? path.substring(1) : path;
    String[] parts = trimmed.split("/", -1);
    if (parts.length >= 2 && DATABASES.equalsIgnoreCase(parts[0]) && !parts[1].isEmpty()) {
      result[0] = parts[1];
      if (parts.length >= 4 && COLLECTIONS.equalsIgnoreCase(parts[2]) && !parts[3].isEmpty()) {
        result[1] = parts[3];
      }
    }
    return result;
  }

  /**
   * Reads the top-level {@code id} string from a JSON request body. Only used for database
   * creation, whose path ({@code /dbs}) does not carry the new database id.
   */
  public static String idFromBody(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    String json = new String(body, UTF_8);
    int i = json.indexOf(ID_KEY);
    if (i < 0) {
      return null;
    }
    i = skipWhitespace(json, i + ID_KEY.length());
    if (i >= json.length() || json.charAt(i) != ':') {
      return null;
    }
    i = skipWhitespace(json, i + 1);
    if (i >= json.length() || json.charAt(i) != '"') {
      return null;
    }
    StringBuilder id = new StringBuilder();
    for (i = i + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '"') {
        return id.toString();
      }
      if (c == '\\') {
        if (++i >= json.length()) {
          return null;
        }
        id.append(json.charAt(i));
      } else {
        id.append(c);
      }
    }
    return null;
  }

  private static int skipWhitespace(String json, int i) {
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    return i;
  }
}
