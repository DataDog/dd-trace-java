package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The carrier keys (headers) the tracer knows by name, each with the encodings carriers commonly
 * need, computed once. Propagators read a carrier's keys through {@link CarrierKeys}, which tells a
 * key's constant from its characters or bytes, so code that handles several headers can switch on
 * the constant instead of comparing strings.
 *
 * <p>A constant's {@link #ordinal()} is a dense index: per-header state, or carrier-specific forms
 * of a header name (a Netty {@code AsciiString}, a gRPC {@code Metadata.Key}), belong in an array
 * of length {@code HeaderSchema.values().length}, indexed by ordinal and filled once, which keeps
 * this class free of carrier dependencies.
 *
 * <p>Names are compared ignoring ASCII case, as carriers such as HTTP do. Keys that aren't known
 * ahead of time (configured header tags, baggage mappings, prefixed keys) have no constant.
 */
public enum HeaderSchema {
  // Datadog
  X_DATADOG_TRACE_ID("x-datadog-trace-id"),
  X_DATADOG_PARENT_ID("x-datadog-parent-id"),
  X_DATADOG_SAMPLING_PRIORITY("x-datadog-sampling-priority"),
  X_DATADOG_ORIGIN("x-datadog-origin"),
  X_DATADOG_TAGS("x-datadog-tags"),
  // W3C trace context
  TRACEPARENT("traceparent"),
  TRACESTATE("tracestate"),
  // W3C baggage
  BAGGAGE("baggage"),
  // B3
  X_B3_TRACE_ID("x-b3-traceid"),
  X_B3_SPAN_ID("x-b3-spanid"),
  X_B3_SAMPLED("x-b3-sampled"),
  B3("b3"),
  // Haystack
  HAYSTACK_TRACE_ID("trace-id"),
  HAYSTACK_SPAN_ID("span-id"),
  HAYSTACK_PARENT_ID("parent-id"),
  // AWS X-Ray
  X_AMZN_TRACE_ID("x-amzn-trace-id"),
  // Data Streams Monitoring
  DD_PATHWAY_CTX_BASE64("dd-pathway-ctx-base64"),
  // forwarding, user agent and client IP, read by every propagation style
  FORWARDED("forwarded"),
  FORWARDED_FOR("forwarded-for"),
  X_FORWARDED_PROTO("x-forwarded-proto"),
  X_FORWARDED_HOST("x-forwarded-host"),
  X_FORWARDED_FOR("x-forwarded-for"),
  X_FORWARDED_PORT("x-forwarded-port"),
  USER_AGENT("user-agent"),
  X_CLIENT_IP("x-client-ip"),
  TRUE_CLIENT_IP("true-client-ip"),
  X_CLUSTER_CLIENT_IP("x-cluster-client-ip"),
  X_REAL_IP("x-real-ip"),
  FASTLY_CLIENT_IP("fastly-client-ip"),
  CF_CONNECTING_IP("cf-connecting-ip"),
  CF_CONNECTING_IP_V6("cf-connecting-ipv6");

  private static final HeaderSchema[] VALUES = values();

  /** The header name, lower case. */
  public final String headerName;

  /** {@link #headerName} in ASCII, for matching or writing a key without encoding it. */
  private final byte[] asciiName;

  /** {@code headerName} must be lower case, as {@code HeaderSchemaTest} checks. */
  HeaderSchema(String headerName) {
    this.headerName = headerName;
    this.asciiName = headerName.getBytes(US_ASCII);
  }

  /** The length of {@link #headerName}, in chars and in ASCII bytes. */
  public int length() {
    return asciiName.length;
  }

  /** {@link #headerName}'s ASCII byte at {@code index}. */
  public byte asciiByte(int index) {
    return asciiName[index];
  }

  /** A copy of {@link #headerName} in ASCII. */
  public byte[] asciiName() {
    return asciiName.clone();
  }

  /** The number of constants: the length of an array indexed by {@link #ordinal()}. */
  public static int count() {
    return VALUES.length;
  }

  /** The constant whose {@link #headerName} is {@code lowerCaseName}, or null. */
  public static HeaderSchema forName(String lowerCaseName) {
    for (HeaderSchema header : VALUES) {
      if (header.headerName.equals(lowerCaseName)) {
        return header;
      }
    }
    return null;
  }

  /** The constant with {@code ordinal}. */
  public static HeaderSchema fromOrdinal(int ordinal) {
    return VALUES[ordinal];
  }
}
