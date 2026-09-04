package datadog.trace.lambda;

import datadog.trace.api.appsec.MediaType;
import datadog.trace.lambda.MultipartSplitter.Part;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  // These bound the work done in this parser only: exceeding any of them degrades the body to a raw
  // string rather than dropping content.
  static final int MAX_BYTES = 1024 * 1024;
  static final int MAX_PARTS = 256;
  static final int MAX_DEPTH = 20;

  /** What a form body's percent-escapes mean when it declares no charset of its own. */
  private static final String DEFAULT_CHARSET = "UTF-8";

  private ContentTypeBodyParser() {}

  /**
   * State shared across a whole parse: the byte and part allowances, and the filenames collected
   * along the way. A multipart part may itself hold a multipart body, so a per-call allowance would
   * be re-satisfied at every nesting level.
   */
  static final class ParseContext {
    private int bytes;
    private int parts = MAX_PARTS;

    ParseContext() {
      this(MAX_BYTES);
    }

    /**
     * @param byteAllowance the total number of characters this parse may read, nesting included
     */
    ParseContext(final int byteAllowance) {
      this.bytes = byteAllowance;
    }

    /** Allocated only once a file part is seen, which most bodies never do. */
    private List<String> filenames;

    int remainingParts() {
      return parts;
    }

    void consumeParts(final int count) {
      parts -= count;
    }

    /**
     * @return {@code false} when the parse can no longer afford to read {@code count} characters
     */
    boolean takeBytes(final int count) {
      if (bytes < count) {
        return false;
      }
      bytes -= count;
      return true;
    }

    /**
     * @return {@code false} once the part allowance is spent. A multipart part and an urlencoded
     *     parameter both draw from it.
     */
    boolean takePart() {
      if (parts == 0) {
        return false;
      }
      parts--;
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
    if (!context.takeBytes(body.length())) {
      log.debug(
          "Byte allowance cannot cover a body of {} chars, keeping raw string", body.length());
      return body;
    }
    final MediaType mediaType = MediaType.parse(contentType);
    if (isJsonOrUntyped(mediaType)) {
      final Object parsed = LambdaEventParser.parseBodyAsJson(body);
      return parsed != null ? parsed : body;
    }
    if ("application".equals(mediaType.getType())
        && "x-www-form-urlencoded".equals(mediaType.getSubtype())) {
      final Object parsed = parseUrlEncoded(body, charsetName(mediaType), context);
      return parsed != null ? parsed : body;
    }
    if ("multipart".equals(mediaType.getType())) {
      final Object parsed = parseMultipart(body, contentType, depth, context);
      return parsed != null ? parsed : body;
    }
    // text/* and everything else stay raw strings. In particular a text/plain body of "12345" must
    // reach the WAF as a String, not as the Double a JSON parse would produce.
    return body;
  }

  static boolean isJsonOrUntyped(final MediaType mediaType) {
    final String subtype = mediaType.getSubtype();
    return mediaType.getType() == null
        || (subtype != null && (subtype.contains("json") || subtype.contains("javascript")));
  }

  /**
   * Parses an {@code application/x-www-form-urlencoded} body into a multimap, matching the shape
   * produced for query parameters.
   *
   * @return the parsed parameters, or {@code null} if nothing usable was found or the body exhausts
   *     the part allowance
   */
  private static Map<String, List<String>> parseUrlEncoded(
      final String body, final String charset, final ParseContext context) {
    if (body.isEmpty()) {
      return null;
    }
    final Map<String, List<String>> parameters = new LinkedHashMap<>();
    final StringTokenizer tokenizer = new StringTokenizer(body, "&");
    while (tokenizer.hasMoreTokens()) {
      if (!context.takePart()) {
        log.debug("Part allowance exhausted, keeping urlencoded body as a raw string");
        return null;
      }
      final String pair = tokenizer.nextToken();
      final int equals = pair.indexOf('=');
      // An empty name is kept rather than dropped: the handler still decodes the parameter, so
      // dropping it would hide its value from the WAF. The Netty body collector keeps it too.
      final String name = decode(equals == -1 ? pair : pair.substring(0, equals), charset);
      parameters
          .computeIfAbsent(name, k -> new ArrayList<>(1))
          .add(equals == -1 ? "" : decode(pair.substring(equals + 1), charset));
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
   * @return the fields found, or {@code null} if the body has no usable boundary, it holds more
   *     parts than the allowance, or it yields no field
   */
  private static Object parseMultipart(
      final String body, final String contentType, final int depth, final ParseContext context) {
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
      log.debug("Part allowance exhausted, keeping multipart body as a raw string");
      return null;
    }
    context.consumeParts(parts.size());

    final Map<String, Object> fields = new LinkedHashMap<>();
    final Map<String, List<Object>> promoted = new HashMap<>();
    for (final Part part : parts) {
      final String disposition = part.contentDisposition;
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
      // A part with no name parameter at all is not a form field, but one named "" is: the handler
      // decodes it, so it is reported under the empty key rather than dropped, as Netty does.
      final String name = MultipartSplitter.parameter(disposition, "name");
      if (name == null) {
        continue;
      }
      final String partContentType = part.contentType;
      final String content = body.substring(part.contentStart, part.contentEnd);
      // A part that declares no type is kept as a raw string
      final Object value =
          partContentType == null || partContentType.isEmpty()
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
   * @param promoted the list each promoted field was given, by name, mutated as fields are
   *     promoted. Tracked rather than inferred from the stored value's type: a part whose body
   *     parsed as a JSON array is itself a List, and appending to it would flatten the two apart.
   */
  private static void addField(
      final Map<String, Object> fields,
      final Map<String, List<Object>> promoted,
      final String name,
      final Object value) {
    final List<Object> values = promoted.get(name);
    if (values != null) {
      values.add(value);
      return;
    }
    // A part value is never null, so an absent key is exactly a null lookup
    final Object existing = fields.get(name);
    if (existing == null) {
      fields.put(name, value);
    } else {
      final List<Object> promotion = new ArrayList<>(2);
      promotion.add(existing);
      promotion.add(value);
      fields.put(name, promotion);
      promoted.put(name, promotion);
    }
  }

  /**
   * Resolves the charset a form body declares, which decides what its percent-escapes mean: {@code
   * %E9} is one character under ISO-8859-1 and an invalid sequence under UTF-8. Reading it the way
   * the handler does keeps the WAF matching on the same value the application consumes.
   *
   * @return the declared charset, or UTF-8 when none is declared or it is not one this JVM has
   */
  private static String charsetName(final MediaType mediaType) {
    String declared = mediaType.getCharset();
    if (declared == null) {
      return DEFAULT_CHARSET;
    }
    // MediaType keeps everything past "charset=", so a parameter after it, or quotes around the
    // value, come along with it
    final int nextParameter = declared.indexOf(';');
    declared = (nextParameter == -1 ? declared : declared.substring(0, nextParameter)).trim();
    if (declared.length() > 1 && declared.charAt(0) == '"' && declared.endsWith("\"")) {
      declared = declared.substring(1, declared.length() - 1);
    }
    if (declared.isEmpty()) {
      return DEFAULT_CHARSET;
    }
    try {
      if (Charset.isSupported(declared)) {
        return declared;
      }
    } catch (final IllegalArgumentException e) {
      // Not a charset name at all: a body may declare anything
    }
    log.debug("Unsupported charset {} declared, decoding the body as UTF-8", declared);
    return DEFAULT_CHARSET;
  }

  /** Percent-decodes a single token, keeping it undecoded rather than dropping it on failure. */
  private static String decode(final String value, final String charset) {
    if (value.isEmpty()) {
      return value;
    }
    try {
      return URLDecoder.decode(value, charset);
    } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
      return value;
    }
  }
}
