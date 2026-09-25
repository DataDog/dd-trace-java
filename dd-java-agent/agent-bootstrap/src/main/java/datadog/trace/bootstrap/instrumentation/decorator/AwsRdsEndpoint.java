package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.Locale;

/**
 * Identity encoded in an Amazon RDS endpoint hostname.
 *
 * <p>RDS hands out DNS names of the form {@code <identifier>.<hash>.<region>.rds.amazonaws.com} (or
 * {@code <identifier>.<hash>.rds.<region>.amazonaws.com.cn} in the China partition). The leading
 * label is the DB instance identifier, the Aurora cluster identifier, a custom endpoint name or an
 * RDS Proxy name depending on the {@code <hash>} label prefix. The Region is always present.
 *
 * <p>Only the endpoint type is recoverable from the name. A cluster endpoint always names a
 * cluster, but the writer instance behind it is not part of the hostname, so this class never
 * claims an instance identifier for a cluster endpoint.
 */
public final class AwsRdsEndpoint {

  public enum Type {
    /** {@code <db-instance-identifier>.<hash>.<region>.rds.amazonaws.com}. */
    INSTANCE("instance"),
    /** {@code <db-cluster-identifier>.cluster-<hash>.<region>.rds.amazonaws.com}. */
    CLUSTER("cluster"),
    /** {@code <db-cluster-identifier>.cluster-ro-<hash>.<region>.rds.amazonaws.com}. */
    CLUSTER_READER("cluster-ro"),
    /** {@code <endpoint-name>.cluster-custom-<hash>.<region>.rds.amazonaws.com}. */
    CLUSTER_CUSTOM("cluster-custom"),
    /** {@code <proxy-name>.proxy-<hash>.<region>.rds.amazonaws.com}. */
    PROXY("proxy");

    private final String tagValue;

    Type(String tagValue) {
      this.tagValue = tagValue;
    }

    public String tagValue() {
      return tagValue;
    }
  }

  private static final AwsRdsEndpoint NOT_RDS = new AwsRdsEndpoint(null, null, null);

  // Applications talk to a handful of databases; the cache only needs to absorb the per-span
  // parse of the same few hostnames.
  private static final DDCache<String, AwsRdsEndpoint> CACHE = DDCaches.newFixedSizeCache(16);

  private final String identifier;
  private final Type type;
  private final String region;

  private AwsRdsEndpoint(String identifier, Type type, String region) {
    this.identifier = identifier;
    this.type = type;
    this.region = region;
  }

  /**
   * Parses an RDS endpoint hostname.
   *
   * @return the identity encoded in the hostname, or {@code null} when the hostname is not an RDS
   *     endpoint.
   */
  public static AwsRdsEndpoint parse(final CharSequence hostname) {
    if (hostname == null || hostname.length() < AMAZONAWS_COM.length()) {
      return null;
    }
    String hostnameString = hostname.toString();
    // Every RDS endpoint, in any partition, ends in ".amazonaws.com" or ".amazonaws.com.cn".
    // Reject everything else before touching the cache so the handful of real RDS hostnames are
    // never evicted by the unbounded stream of non-AWS database hosts, and so those hosts never
    // pay for the label split.
    if (!isPlausibleRdsHostname(hostnameString)) {
      return null;
    }
    AwsRdsEndpoint endpoint = CACHE.computeIfAbsent(hostnameString, AwsRdsEndpoint::doParse);
    return endpoint == NOT_RDS ? null : endpoint;
  }

  private static final String AMAZONAWS_COM = ".amazonaws.com";
  private static final String AMAZONAWS_COM_CN = ".amazonaws.com.cn";

  /**
   * O(1) check that the hostname ends with {@code .amazonaws.com} or {@code .amazonaws.com.cn},
   * ignoring case and tolerating the same trailing {@code .} and {@code :port} that {@link
   * #doParse} accepts, so the gate and the parser never disagree on what is RDS-shaped. Anchoring
   * on the suffix (rather than scanning for the substring) also rejects hostnames that merely
   * contain {@code .amazonaws.com} followed by other labels.
   */
  static boolean isPlausibleRdsHostname(final String hostname) {
    int end = hostname.length();
    if (end > 0 && hostname.charAt(end - 1) == '.') {
      end--;
    }
    end = stripPort(hostname, end);
    return endsWithIgnoreCase(hostname, end, AMAZONAWS_COM)
        || endsWithIgnoreCase(hostname, end, AMAZONAWS_COM_CN);
  }

  /** Returns the end index with a trailing {@code :port} removed. Bounded to 5 digits. */
  private static int stripPort(final String hostname, final int end) {
    int i = end;
    int digits = 0;
    while (i > 0 && digits < 5 && isAsciiDigit(hostname.charAt(i - 1))) {
      i--;
      digits++;
    }
    return digits > 0 && i > 0 && hostname.charAt(i - 1) == ':' ? i - 1 : end;
  }

  private static boolean isAsciiDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean endsWithIgnoreCase(
      final String hostname, final int end, final String suffix) {
    final int length = suffix.length();
    return end >= length && hostname.regionMatches(true, end - length, suffix, 0, length);
  }

  /** The DB instance identifier, cluster identifier, custom endpoint name or proxy name. */
  public String identifier() {
    return identifier;
  }

  public Type type() {
    return type;
  }

  /** The AWS Region the endpoint lives in, for example {@code us-east-1}. */
  public String region() {
    return region;
  }

  private static AwsRdsEndpoint doParse(final String rawHostname) {
    String hostname = rawHostname.toLowerCase(Locale.ROOT);
    int end = hostname.length();
    // Strip a trailing dot from a fully qualified name and a :port suffix if one leaked in.
    if (end > 0 && hostname.charAt(end - 1) == '.') {
      end--;
    }
    int colon = hostname.indexOf(':');
    if (colon >= 0) {
      end = Math.min(end, colon);
    }
    String[] labels = hostname.substring(0, end).split("\\.", -1);

    // <identifier>.<hash>.<region>.rds.amazonaws.com
    if (labels.length == 6
        && "rds".equals(labels[3])
        && "amazonaws".equals(labels[4])
        && "com".equals(labels[5])) {
      return build(labels[0], labels[1], labels[2]);
    }
    // <identifier>.<hash>.rds.<region>.amazonaws.com.cn
    if (labels.length == 7
        && "rds".equals(labels[2])
        && "amazonaws".equals(labels[4])
        && "com".equals(labels[5])
        && "cn".equals(labels[6])) {
      return build(labels[0], labels[1], labels[3]);
    }
    return NOT_RDS;
  }

  private static AwsRdsEndpoint build(
      final String identifier, final String hash, final String region) {
    if (identifier.isEmpty() || !isRegion(region)) {
      return NOT_RDS;
    }
    Type type;
    String suffix;
    if (hash.startsWith("cluster-ro-")) {
      type = Type.CLUSTER_READER;
      suffix = hash.substring("cluster-ro-".length());
    } else if (hash.startsWith("cluster-custom-")) {
      type = Type.CLUSTER_CUSTOM;
      suffix = hash.substring("cluster-custom-".length());
    } else if (hash.startsWith("cluster-")) {
      type = Type.CLUSTER;
      suffix = hash.substring("cluster-".length());
    } else if (hash.startsWith("proxy-")) {
      type = Type.PROXY;
      suffix = hash.substring("proxy-".length());
    } else {
      type = Type.INSTANCE;
      suffix = hash;
    }
    if (!isHash(suffix)) {
      return NOT_RDS;
    }
    return new AwsRdsEndpoint(identifier, type, region);
  }

  /** The account-scoped hash RDS appends to every endpoint: lowercase alphanumerics only. */
  private static boolean isHash(final String value) {
    if (value.length() < 8 || value.length() > 16) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
        return false;
      }
    }
    return true;
  }

  /** An AWS Region code such as us-east-1, us-gov-west-1, cn-north-1 or eu-isoe-west-1. */
  private static boolean isRegion(final String value) {
    int length = value.length();
    if (length < 8 || length > 24) {
      return false;
    }
    int dashes = 0;
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      if (c == '-') {
        dashes++;
      } else if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
        return false;
      }
    }
    // Every Region ends in a digit and has at least two dashes (e.g. us-east-1).
    return dashes >= 2 && value.charAt(0) != '-' && Character.isDigit(value.charAt(length - 1));
  }
}
