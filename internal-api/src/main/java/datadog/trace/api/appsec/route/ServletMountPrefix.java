package datadog.trace.api.appsec.route;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Mount prefix of a servlet request: the decoded path served before the route matched by a
 * framework that runs inside a servlet (e.g. Spring MVC). It is passed as the decoded prefix of
 * {@link NormalizedRouteHelper#tag}. Takes the raw request values so it does not depend on any
 * servlet API version.
 *
 * <p>When the framework cached its lookup path (Spring 5.3+ {@code UrlPathHelper.PATH} attribute)
 * and the request path within the context, decoded with the request encoding as Spring does (or
 * left undecoded, for {@code urlDecode=false}), ends with it, the part before the lookup path is
 * the servlet mount: it must be empty ({@code alwaysUseFullPath=true}, default mapping) or the
 * servlet path, else the lookup path did not come from the request path (e.g. the container removed
 * dot segments) and it is ignored. Otherwise the prefix is the context path, plus the servlet path
 * when the servlet has a path mapping ({@code /api/*}). The request encoding is only used to match
 * the lookup path: raw parts of the returned prefix are decoded as UTF-8, the encoding of RFC-1125
 * literals, so the renderer encodes them back to the bytes of the request URI. The emitted context
 * path never carries {@code ;} parameters (e.g. {@code ;jsessionid=}).
 */
public final class ServletMountPrefix {

  /** Default request encoding of the Servlet spec, also used by Spring's {@code UrlPathHelper}. */
  private static final Charset DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;

  private static final String JSESSIONID = ";jsessionid=";

  private ServletMountPrefix() {}

  /**
   * Computes the decoded mount prefix; returns "" when there is none.
   *
   * @param requestUri raw request URI path, without query ({@code getRequestURI()})
   * @param contextPath raw context path ({@code getContextPath()})
   * @param servletPath decoded servlet path ({@code getServletPath()})
   * @param pathMapping whether the servlet has a path mapping ({@code /api/*}): pathInfo non-null,
   *     or Servlet 4+ {@code MappingMatch.PATH}
   * @param cachedLookupPath path the route was matched against, as cached by the framework, or null
   * @param characterEncoding request character encoding name the framework decoded the lookup path
   *     with; ISO-8859-1 when null, empty or unsupported
   */
  public static String of(
      String requestUri,
      String contextPath,
      String servletPath,
      boolean pathMapping,
      String cachedLookupPath,
      String characterEncoding) {
    String inferred =
        inferred(requestUri, contextPath, servletPath, cachedLookupPath, characterEncoding);
    return inferred != null ? inferred : fallback(contextPath, servletPath, pathMapping);
  }

  /**
   * Prefix inferred from the cached lookup path (see {@link #of}); null when there is no cached
   * lookup path or it cannot be located in the request path, then use {@link #fallback}.
   */
  public static String inferred(
      String requestUri,
      String contextPath,
      String servletPath,
      String cachedLookupPath,
      String characterEncoding) {
    if (cachedLookupPath == null) {
      return null;
    }
    String rawContextPath = contextPath == null ? "" : contextPath;
    String rawBeforeLookupPath =
        rawBeforeLookupPath(requestUri, rawContextPath, cachedLookupPath, characterEncoding);
    if (rawBeforeLookupPath == null) {
      return null;
    }
    String decodedContextPath = decodedContextPath(rawContextPath);
    if (rawBeforeLookupPath.isEmpty()) {
      return decodedContextPath;
    }
    String mount = collapseSlashes(RouteEncoding.decodePath(rawBeforeLookupPath));
    // anything else than the servlet mount is request-specific text, not a prefix
    return mount.equals(servletPath) ? decodedContextPath + mount : null;
  }

  /**
   * Prefix without a usable lookup path: the context path, plus the servlet path when the servlet
   * has a path mapping.
   */
  public static String fallback(String contextPath, String servletPath, boolean pathMapping) {
    String decodedContextPath = decodedContextPath(contextPath == null ? "" : contextPath);
    return pathMapping && servletPath != null
        ? decodedContextPath + servletPath
        : decodedContextPath;
  }

  /** Context path as emitted: without {@code ;} parameters, decoded. */
  private static String decodedContextPath(String rawContextPath) {
    return RouteEncoding.decodePath(removeSemicolonContent(rawContextPath));
  }

  /**
   * Raw part of the request path within the context that precedes the lookup path, without {@code
   * ;} content; null when there is no lookup path or the request path does not end with it. Both
   * settings of Spring's {@code removeSemicolonContent} are tried: all {@code ;} content removed
   * (default), or only {@code ;jsessionid}.
   */
  private static String rawBeforeLookupPath(
      String requestUri, String rawContextPath, String lookupPath, String characterEncoding) {
    if (lookupPath == null || requestUri == null || !requestUri.startsWith(rawContextPath)) {
      return null;
    }
    String rawPath = requestUri.substring(rawContextPath.length());
    String withoutSemicolonContent = removeSemicolonContent(rawPath);
    int end = rawEndBeforeLookupPath(withoutSemicolonContent, lookupPath, characterEncoding);
    if (end >= 0) {
      return withoutSemicolonContent.substring(0, end);
    }
    String withoutJsessionid = removeJsessionid(rawPath);
    if (withoutJsessionid.equals(withoutSemicolonContent)) {
      return null;
    }
    end = rawEndBeforeLookupPath(withoutJsessionid, lookupPath, characterEncoding);
    return end < 0 ? null : removeSemicolonContent(withoutJsessionid.substring(0, end));
  }

  /**
   * Decodes the raw path with the request encoding and collapses {@code /} runs, as Spring does for
   * its lookup path; when the result ends with the lookup path, returns the raw index where the
   * lookup path starts, else -1 (also when that position falls inside a decoded escape sequence).
   * Invalid or truncated escapes are kept as is and {@code +} is not a space. When the decoded path
   * does not match, an undecoded lookup path ({@code urlDecode=false}) is also recognized.
   */
  static int rawEndBeforeLookupPath(String rawPath, String lookupPath, String characterEncoding) {
    boolean escaped = rawPath.indexOf('%') >= 0;
    if (!escaped && rawPath.indexOf("//") < 0) {
      // nothing to decode or collapse: same result as the decoding path, without allocating
      return rawSuffixStart(rawPath, lookupPath);
    }
    int end = decodedEndBeforeLookupPath(rawPath, lookupPath, characterEncoding);
    if (end >= 0 || !escaped) {
      return end;
    }
    // urlDecode=false: Spring kept the escapes in its lookup path
    if (rawPath.indexOf("//") < 0) {
      return rawSuffixStart(rawPath, lookupPath);
    }
    return collapseSlashes(rawPath).equals(lookupPath) ? 0 : -1;
  }

  private static int rawSuffixStart(String rawPath, String lookupPath) {
    return rawPath.endsWith(lookupPath) ? rawPath.length() - lookupPath.length() : -1;
  }

  /** Decoding path of {@link #rawEndBeforeLookupPath}, without the shortcuts. */
  static int decodedEndBeforeLookupPath(
      String rawPath, String lookupPath, String characterEncoding) {
    int len = rawPath.length();
    StringBuilder decoded = new StringBuilder(len);
    // decoded index -> raw index; -1 inside the chars decoded from one escape sequence
    int[] rawIndex = new int[len + 1];
    Charset charset = null;
    byte[] bytes = null;
    int i = 0;
    while (i < len) {
      if (!isEscape(rawPath, i)) {
        append(decoded, rawIndex, rawPath.charAt(i), i);
        i++;
        continue;
      }
      if (charset == null) {
        charset = charset(characterEncoding);
        bytes = new byte[len / 3];
      }
      int start = i;
      int n = 0;
      do {
        bytes[n++] =
            (byte)
                ((RouteEncoding.hexValue(rawPath.charAt(i + 1)) << 4)
                    | RouteEncoding.hexValue(rawPath.charAt(i + 2)));
        i += 3;
      } while (i < len && isEscape(rawPath, i));
      String chunk = new String(bytes, 0, n, charset);
      int from = start;
      for (int c = 0; c < chunk.length(); c++) {
        if (append(decoded, rawIndex, chunk.charAt(c), from)) {
          from = -1;
        }
      }
    }
    int decodedLength = decoded.length();
    rawIndex[decodedLength] = len;
    int lookupLength = lookupPath.length();
    if (lookupLength > decodedLength
        || !decoded.substring(decodedLength - lookupLength).equals(lookupPath)) {
      return -1;
    }
    return rawIndex[decodedLength - lookupLength];
  }

  /** Appends {@code c} unless it repeats a {@code /}; returns whether it was appended. */
  private static boolean append(StringBuilder decoded, int[] rawIndex, char c, int raw) {
    int length = decoded.length();
    if (c == '/' && length > 0 && decoded.charAt(length - 1) == '/') {
      return false;
    }
    rawIndex[length] = raw;
    decoded.append(c);
    return true;
  }

  /**
   * Removes every {@code ;} parameter of each segment, as Spring does before matching; returns the
   * same instance when there is none.
   */
  public static String removeSemicolonContent(String path) {
    int semicolon = path.indexOf(';');
    if (semicolon < 0) {
      return path;
    }
    StringBuilder sb = new StringBuilder(path.length());
    int start = 0;
    while (semicolon >= 0) {
      sb.append(path, start, semicolon);
      int slash = path.indexOf('/', semicolon);
      if (slash < 0) {
        return sb.toString();
      }
      start = slash;
      semicolon = path.indexOf(';', start);
    }
    return sb.append(path, start, path.length()).toString();
  }

  /** Removes the first {@code ;jsessionid=...} parameter (case-insensitive key), as Spring does. */
  static String removeJsessionid(String path) {
    int index = indexOfIgnoreCase(path, JSESSIONID);
    if (index < 0) {
      return path;
    }
    for (int i = index + JSESSIONID.length(); i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == ';' || c == '/') {
        return path.substring(0, index) + path.substring(i);
      }
    }
    return path.substring(0, index);
  }

  private static int indexOfIgnoreCase(String s, String key) {
    for (int i = s.indexOf(';');
        i >= 0 && i + key.length() <= s.length();
        i = s.indexOf(';', i + 1)) {
      if (s.regionMatches(true, i, key, 0, key.length())) {
        return i;
      }
    }
    return -1;
  }

  /** Replaces each run of {@code /} with a single {@code /}, as Spring's sanitized path. */
  static String collapseSlashes(String path) {
    int start = path.indexOf("//");
    if (start < 0) {
      return path;
    }
    StringBuilder sb = new StringBuilder(path.length());
    sb.append(path, 0, start + 1);
    for (int i = start + 1; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c != '/' || path.charAt(i - 1) != '/') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static boolean isEscape(String s, int i) {
    return s.charAt(i) == '%'
        && i + 2 < s.length()
        && RouteEncoding.hexValue(s.charAt(i + 1)) >= 0
        && RouteEncoding.hexValue(s.charAt(i + 2)) >= 0;
  }

  static Charset charset(String characterEncoding) {
    if (characterEncoding == null || characterEncoding.isEmpty()) {
      return DEFAULT_CHARSET;
    }
    try {
      return Charset.forName(characterEncoding);
    } catch (IllegalArgumentException unsupported) {
      return DEFAULT_CHARSET;
    }
  }
}
