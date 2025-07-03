package datadog.trace.api.rum;

import java.util.HashMap;
import java.util.Map;

/*
 * Cache for ContentTypes answers
 */
public class ContentTypesCache {
  static final short MAX_ITEMS = 1_000;
  private final Map<String, Boolean> map;

  /*
   * Constructs a new ContentTypes cache for the provided types
   *
   * @param contentTypes the list of ContentTypes it will store answers for
   */
  public ContentTypesCache(String[] contentTypes) {
    this.map = new HashMap<>();
    for (String contentType : contentTypes) {
      this.map.put(contentType, true);
    }
  }

  public boolean contains(String headerValue) {
    return this.map.computeIfAbsent(headerValue, this::shouldProcess);
  }

  private boolean shouldProcess(String headerValue) {
    Boolean typeAllowed = this.map.get(getContentType(headerValue));
    return typeAllowed != null && typeAllowed;
  }

  private String getContentType(String headerValue) {
    // multipart/* are expected to contain boundary unique values
    // let's abort early to avoid exploding the cache
    // additionally, if the cache is already too big, let's also abort
    if (this.map.size() > MAX_ITEMS || headerValue.startsWith("multipart/")) {
      return null;
    }
    // RFC 2045 defines optional parameters always behind a semicolon
    int semicolon = headerValue.indexOf(";");
    return semicolon != -1 ? headerValue.substring(0, semicolon) : headerValue;
  }
}
