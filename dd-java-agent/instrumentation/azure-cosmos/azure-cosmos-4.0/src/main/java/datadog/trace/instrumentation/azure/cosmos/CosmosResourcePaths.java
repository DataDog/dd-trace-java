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
    int depth = 0;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
      } else if (c == '"') {
        int end = endOfString(json, i);
        if (end < 0) {
          return null;
        }
        if (depth == 1 && json.startsWith(ID_KEY, i) && end == i + ID_KEY.length() - 1) {
          int colon = skipWhitespace(json, end + 1);
          if (colon < json.length() && json.charAt(colon) == ':') {
            int value = skipWhitespace(json, colon + 1);
            if (value >= json.length() || json.charAt(value) != '"') {
              return null;
            }
            return readString(json, value);
          }
        }
        i = end;
      }
    }
    return null;
  }

  /** Returns the index of the quote closing the string that opens at {@code start}, or -1. */
  private static int endOfString(String json, int start) {
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        return i;
      }
    }
    return -1;
  }

  private static String readString(String json, int start) {
    StringBuilder value = new StringBuilder();
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '"') {
        return value.toString();
      }
      if (c == '\\') {
        if (++i >= json.length()) {
          return null;
        }
        value.append(json.charAt(i));
      } else {
        value.append(c);
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
