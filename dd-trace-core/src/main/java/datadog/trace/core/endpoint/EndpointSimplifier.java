package datadog.trace.core.endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simplifies HTTP URLs to infer endpoints for APM trace metrics.
 *
 * <p>This class extracts paths from URLs and simplifies them by replacing common parameter patterns
 * (IDs, hex strings, etc.) with standardized placeholders. This reduces cardinality while
 * maintaining endpoint recognizability.
 *
 * <p>Example:
 *
 * <pre>
 *   /users/123/orders/abc-def-456 → /users/{param:int}/orders/{param:hex_id}
 * </pre>
 */
public class EndpointSimplifier {
  private static final Logger log = LoggerFactory.getLogger(EndpointSimplifier.class);

  /**
   * Regex to extract path from URL. Captures the path component between optional scheme+host and
   * optional query string. Group "path" contains the extracted path.
   */
  private static final Pattern URL_PATH_PATTERN =
      Pattern.compile("^(?:[a-z]+://(?:[^?/]+))?(?<path>/[^?]*)(?:(\\?).*)?$");

  /**
   * Maximum number of path segments to keep after simplification. Prevents cardinality explosion
   * from very deep URLs.
   */
  private static final int MAX_SEGMENTS = 8;

  /** Default endpoint when path is empty or cannot be processed. */
  private static final String DEFAULT_ENDPOINT = "/";

  /**
   * Simplifies a full URL to an inferred endpoint.
   *
   * <p>Process:
   *
   * <ol>
   *   <li>Extract path from URL
   *   <li>Split path into segments
   *   <li>Keep only first 8 non-empty segments
   *   <li>Simplify each segment using pattern matching
   *   <li>Reconstruct path with simplified segments
   * </ol>
   *
   * @param url the full URL (e.g., "https://example.com/users/123?foo=bar")
   * @return the simplified endpoint (e.g., "/users/{param:int}")
   */
  public static String simplifyUrl(@Nullable String url) {
    if (url == null || url.isEmpty()) {
      return DEFAULT_ENDPOINT;
    }

    String path = extractPath(url);
    if (path == null || path.isEmpty()) {
      return DEFAULT_ENDPOINT;
    }

    return simplifyPath(path);
  }

  /**
   * Extracts the path component from a URL.
   *
   * <p>Handles various URL formats:
   *
   * <ul>
   *   <li>Full URLs: "http://example.com/path" → "/path"
   *   <li>Path-only: "/path" → "/path"
   *   <li>With query: "/path?query" → "/path"
   * </ul>
   *
   * @param url the URL to extract from
   * @return the extracted path, or null if extraction fails
   */
  @Nullable
  static String extractPath(String url) {
    try {
      Matcher matcher = URL_PATH_PATTERN.matcher(url);
      if (matcher.matches()) {
        return matcher.group("path");
      }
    } catch (Exception e) {
      log.debug("Failed to extract path from URL: {}", url, e);
    }
    return null;
  }

  /**
   * Simplifies a URL path by replacing segments with patterns.
   *
   * <p>Example:
   *
   * <pre>
   *   /users/123/orders/abc-def-456 → /users/{param:int}/orders/{param:hex_id}
   * </pre>
   *
   * @param path the URL path to simplify
   * @return the simplified path
   */
  public static String simplifyPath(String path) {
    if (path == null || path.isEmpty()) {
      return DEFAULT_ENDPOINT;
    }

    // Special case: root path
    if (path.equals("/")) {
      return DEFAULT_ENDPOINT;
    }

    List<String> segments = splitAndLimitSegments(path, MAX_SEGMENTS);

    // If no segments remain after filtering, return root
    if (segments.isEmpty()) {
      return DEFAULT_ENDPOINT;
    }

    // Simplify each segment and reconstruct path
    StringBuilder result = new StringBuilder();
    for (String segment : segments) {
      result.append('/');
      result.append(SegmentPattern.simplify(segment));
    }

    return result.toString();
  }

  /**
   * Splits a path into segments and limits to the first N non-empty segments.
   *
   * <p>Example:
   *
   * <pre>
   *   "/users//123/orders" with limit 3 → ["users", "123", "orders"]
   * </pre>
   *
   * @param path the path to split
   * @param maxSegments maximum number of segments to keep
   * @return list of non-empty segments (limited)
   */
  static List<String> splitAndLimitSegments(String path, int maxSegments) {
    List<String> segments = new ArrayList<>(maxSegments);

    // Manually split on '/' without regex to avoid forbidden API
    int start = 0;
    int length = path.length();

    for (int i = 0; i < length; i++) {
      if (path.charAt(i) == '/') {
        if (i > start) {
          // Found a non-empty segment
          segments.add(path.substring(start, i));
          if (segments.size() >= maxSegments) {
            return segments;
          }
        }
        start = i + 1;
      }
    }

    // Add final segment if exists
    if (start < length) {
      segments.add(path.substring(start));
    }

    return segments;
  }

  /**
   * Simplifies a single path segment using pattern matching. This is a convenience method
   * delegating to {@link SegmentPattern#simplify(String)}.
   *
   * @param segment the segment to simplify
   * @return the simplified segment
   */
  public static String simplifySegment(String segment) {
    return SegmentPattern.simplify(segment);
  }
}
