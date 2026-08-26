package datadog.trace.lambda;

import datadog.trace.api.appsec.MediaType;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a Lambda request body into the shape the AppSec WAF expects. The declared {@code
 * Content-Type} decides how the body is structured; a best-effort JSON parse handles both the
 * JSON-ish types and the case where no type is declared at all.
 *
 * <p>A body is never dropped: any type we cannot structure — and any parse failure — degrades to
 * the raw {@link String}, which the WAF can still match string rules against.
 */
final class ContentTypeBodyParser {

  private static final Logger log = LoggerFactory.getLogger(ContentTypeBodyParser.class);

  // These bound the work done in this parser only. The WAF truncates its inputs independently, so
  // exceeding them is wasteful rather than incorrect.
  static final int MAX_DEPTH = 20;
  static final int MAX_ELEMENTS = 256;

  private ContentTypeBodyParser() {}

  /** Parses a decoded request body according to its {@code Content-Type}. */
  static Object parseBody(final String body, final String contentType) {
    return dispatch(body, contentType, 0);
  }

  static Object dispatch(final String body, final String contentType, final int depth) {
    if (body == null) {
      return null;
    }
    if (depth >= MAX_DEPTH) {
      log.debug("Body nesting depth {} reached, keeping raw string", depth);
      return body;
    }
    if (contentType == null || contentType.trim().isEmpty() || isJsonLike(contentType)) {
      final Object parsed = LambdaEventParser.parseBodyAsJson(body);
      return parsed != null ? parsed : body;
    }
    final MediaType mediaType = MediaType.parse(contentType);
    if ("application".equals(mediaType.getType())
        && "x-www-form-urlencoded".equals(mediaType.getSubtype())) {
      final Object parsed = parseUrlEncoded(body);
      return parsed != null ? parsed : body;
    }
    // text/*, multipart/* and everything else stay raw strings. In particular a text/plain body of
    // "12345" must reach the WAF as a String, not as the Double a JSON parse would produce.
    return body;
  }

  static boolean isJsonLike(final String contentType) {
    if (contentType == null) {
      return false;
    }
    final String lower = contentType.toLowerCase(Locale.ROOT);
    return lower.contains("json") || lower.contains("javascript");
  }

  /**
   * Parses an {@code application/x-www-form-urlencoded} body into a multimap, matching the shape
   * produced for query parameters.
   *
   * @return the parsed parameters, or {@code null} if nothing usable was found
   */
  private static Map<String, List<String>> parseUrlEncoded(final String body) {
    if (body.isEmpty()) {
      return null;
    }
    final Map<String, List<String>> parameters = new LinkedHashMap<>();
    int pairs = 0;
    final StringTokenizer tokenizer = new StringTokenizer(body, "&");
    while (tokenizer.hasMoreTokens() && pairs < MAX_ELEMENTS) {
      final String pair = tokenizer.nextToken();
      pairs++;
      final int equals = pair.indexOf('=');
      final String name = decode(equals == -1 ? pair : pair.substring(0, equals));
      if (!name.isEmpty()) {
        parameters
            .computeIfAbsent(name, k -> new ArrayList<>(1))
            .add(equals == -1 ? "" : decode(pair.substring(equals + 1)));
      }
    }
    if (parameters.isEmpty()) {
      return null;
    }
    log.debug("Body parsed as {} urlencoded parameters", parameters.size());
    return parameters;
  }

  /** Percent-decodes a single token, keeping it undecoded rather than dropping it on failure. */
  private static String decode(final String value) {
    if (value.isEmpty()) {
      return value;
    }
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
      return value;
    }
  }
}
