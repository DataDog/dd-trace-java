package datadog.trace.lambda;

import datadog.trace.api.appsec.MediaType;
import datadog.trace.lambda.MultipartSplitter.Part;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a Lambda request body into the shape the AppSec WAF expects. The declared {@code
 * Content-Type} decides how the body is structured; a best-effort JSON parse handles the JSON-ish
 * types and a top-level body that declares no type at all.
 *
 * <p>A body is never dropped: any type we cannot structure — and any parse failure — degrades to
 * the raw {@link String}, which the WAF can still match string rules against.
 */
final class ContentTypeBodyParser {

  private static final Logger log = LoggerFactory.getLogger(ContentTypeBodyParser.class);

  // These bound the work done in this parser only. Exceeding any of them degrades the body to a raw
  // string rather than dropping content, and the WAF truncates its inputs independently anyway.
  static final int MAX_DEPTH = 20;
  static final int MAX_ELEMENTS = 256;
  static final int MAX_PARTS = 256;
  static final int MAX_MULTIPART_SIZE = 1_000_000;

  private ContentTypeBodyParser() {}

  /**
   * State shared across a whole parse: the element and part allowances, and the filenames collected
   * along the way. A multipart part may itself hold an urlencoded or multipart body, so a per-call
   * cap would multiply across nesting levels and a per-call list would lose nested file parts.
   */
  static final class ParseContext {
    private int parts = MAX_PARTS;
    private int elements = MAX_ELEMENTS;

    /** Allocated only once a file part is seen, which most bodies never do. */
    private List<String> filenames;

    int remainingParts() {
      return parts;
    }

    void consumeParts(final int count) {
      parts -= count;
    }

    /**
     * @return {@code false} once the element allowance is spent
     */
    boolean takeElement() {
      if (elements == 0) {
        return false;
      }
      elements--;
      return true;
    }

    void addFilename(final String filename) {
      if (filenames == null) {
        filenames = new ArrayList<>(2);
      }
      filenames.add(filename);
    }

    /**
     * @return the filenames of the multipart file parts found, in body order, empty when there were
     *     none
     */
    List<String> filenames() {
      return filenames == null ? Collections.emptyList() : filenames;
    }
  }

  /** Parses a decoded request body according to its {@code Content-Type}. */
  static Object parseBody(final String body, final String contentType) {
    return parseBody(body, contentType, new ParseContext());
  }

  /**
   * Parses a decoded request body according to its {@code Content-Type}.
   *
   * @param context also collects the filenames of any multipart file parts found, which the caller
   *     reports separately from the body
   */
  static Object parseBody(final String body, final String contentType, final ParseContext context) {
    return dispatch(body, contentType, 0, context);
  }

  static Object dispatch(
      final String body, final String contentType, final int depth, final ParseContext context) {
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
      final Object parsed = parseUrlEncoded(body, context);
      return parsed != null ? parsed : body;
    }
    if ("multipart".equals(mediaType.getType())) {
      final Object parsed = parseMultipart(body, contentType, depth, context, MAX_MULTIPART_SIZE);
      return parsed != null ? parsed : body;
    }
    // text/* and everything else stay raw strings. In particular a text/plain body of "12345" must
    // reach the WAF as a String, not as the Double a JSON parse would produce.
    return body;
  }

  static boolean isJsonLike(final String contentType) {
    if (contentType == null) {
      return false;
    }
    // Match on the type and subtype only. Parameters such as a multipart boundary are chosen by
    // the client, so "multipart/form-data; boundary=--json" must not reach the JSON parser and
    // thereby skip multipart parsing entirely.
    final int semicolon = contentType.indexOf(';');
    final String essence =
        (semicolon == -1 ? contentType : contentType.substring(0, semicolon))
            .toLowerCase(Locale.ROOT);
    return essence.contains("json") || essence.contains("javascript");
  }

  /**
   * Parses an {@code application/x-www-form-urlencoded} body into a multimap, matching the shape
   * produced for query parameters.
   *
   * @return the parsed parameters, or {@code null} if nothing usable was found or the body exhausts
   *     the element allowance
   */
  private static Map<String, List<String>> parseUrlEncoded(
      final String body, final ParseContext context) {
    if (body.isEmpty()) {
      return null;
    }
    final Map<String, List<String>> parameters = new LinkedHashMap<>();
    final StringTokenizer tokenizer = new StringTokenizer(body, "&");
    while (tokenizer.hasMoreTokens()) {
      if (!context.takeElement()) {
        // Bail out rather than hand the WAF a truncated map: a parameter dropped here would be
        // invisible to every rule, whereas the raw body can still be string-matched.
        log.debug("Element allowance exhausted, keeping urlencoded body as a raw string");
        return null;
      }
      final String pair = tokenizer.nextToken();
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

  /**
   * Parses a {@code multipart/*} body into its form fields.
   *
   * @param sizeLimit the largest body to attempt, or {@code 0} to disable multipart parsing
   * @return the fields found, or {@code null} if the body is too large, has no usable boundary,
   *     holds more parts than the allowance, or yields no field
   */
  static Object parseMultipart(
      final String body,
      final String contentType,
      final int depth,
      final ParseContext context,
      final int sizeLimit) {
    if (sizeLimit <= 0 || body.length() > sizeLimit) {
      log.debug("Multipart body of {} chars not parsed, keeping raw string", body.length());
      return null;
    }
    final String boundary = MultipartSplitter.extractBoundary(contentType);
    if (boundary == null) {
      log.debug("Multipart body without a usable boundary, keeping raw string");
      return null;
    }
    // One over the allowance, so that a body holding more parts than may be read is distinguishable
    // from one holding exactly the allowance
    final int allowance = context.remainingParts();
    final List<Part> parts = MultipartSplitter.split(body, boundary, allowance + 1);
    if (parts.size() > allowance) {
      // Bail out rather than hand the WAF a truncated map, as the urlencoded path does:
      // the raw body can still be string-matched.
      log.debug("Part allowance exhausted, keeping multipart body as a raw string");
      return null;
    }
    context.consumeParts(parts.size());

    final Map<String, Object> fields = new LinkedHashMap<>();
    final Set<String> promoted = new HashSet<>();
    for (final Part part : parts) {
      final String disposition = part.header("content-disposition");
      if (disposition == null) {
        continue;
      }
      final String filename = MultipartSplitter.parameter(disposition, "filename");
      if (filename != null) {
        if (!filename.isEmpty()) {
          context.addFilename(filename);
        }
        continue;
      }
      final String name = MultipartSplitter.parameter(disposition, "name");
      if (name == null || name.isEmpty()) {
        continue;
      }
      final String partContentType = part.header("content-type");
      final String content = body.substring(part.contentStart, part.contentEnd);
      // A part that declares no type is kept as a raw string
      final Object value =
          partContentType == null || partContentType.trim().isEmpty()
              ? content
              : dispatch(content, partContentType, depth + 1, context);
      addField(fields, promoted, name, value);
    }
    return fields.isEmpty() ? null : fields;
  }

  /**
   * Accumulates a field as a scalar on first sight and promotes it to a list on repeat.
   * Deliberately a different shape from urlencoded's always-a-list, matching the peer tracers.
   *
   * @param promoted the names already promoted to a list, mutated as fields are promoted
   */
  @SuppressWarnings("unchecked")
  private static void addField(
      final Map<String, Object> fields,
      final Set<String> promoted,
      final String name,
      final Object value) {
    // A part value is never null, so an absent key is exactly a null lookup
    final Object existing = fields.get(name);
    if (existing == null) {
      fields.put(name, value);
    } else if (promoted.contains(name)) {
      ((List<Object>) existing).add(value);
    } else {
      // Promotion is tracked rather than inferred from the stored value's type: a part whose body
      // parsed as a JSON array is itself a List, and appending to it would flatten the two apart.
      final List<Object> values = new ArrayList<>(2);
      values.add(existing);
      values.add(value);
      fields.put(name, values);
      promoted.add(name);
    }
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
