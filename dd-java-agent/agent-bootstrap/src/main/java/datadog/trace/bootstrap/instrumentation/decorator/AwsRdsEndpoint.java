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
    /**
     * {@code <proxy-name>.proxy-<hash>.<region>.rds.amazonaws.com}, or a user-defined endpoint
     * {@code <endpoint-name>.endpoint.proxy-<hash>.<region>.rds.amazonaws.com}.
     */
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
    if (hostname == null || hostname.length() < SHORTEST_DNS_SUFFIX.length()) {
      return null;
    }
    String hostnameString = hostname.toString();
    // Every RDS endpoint ends in a known AWS partition DNS suffix. Reject everything else before
    // touching the cache so the handful of real RDS hostnames are never
    // evicted by the unbounded stream of non-AWS database hosts, and so those hosts never pay for
    // the label split.
    if (!isPlausibleRdsHostname(hostnameString)) {
      return null;
    }
    AwsRdsEndpoint endpoint = CACHE.computeIfAbsent(hostnameString, AwsRdsEndpoint::doParse);
    return endpoint == NOT_RDS ? null : endpoint;
  }

  // AWS partition DNS suffixes that carry RDS endpoints, from the AWS SDK endpoint partition data.
  private static final String[] DNS_SUFFIXES = {
    ".amazonaws.com",
    ".amazonaws.com.cn",
    ".c2s.ic.gov",
    ".sc2s.sgov.gov",
    ".cloud.adc-e.uk",
    ".csp.hci.ic.gov",
    ".amazonaws.eu"
  };
  // The shortest suffix (".c2s.ic.gov"), used as a cheap length floor before any work.
  private static final String SHORTEST_DNS_SUFFIX = ".c2s.ic.gov";

  // The same suffixes ordered longest first, so doParse strips ".amazonaws.com.cn" rather than
  // shortening it to ".amazonaws.com" and misreading the labels.
  private static final String[] LONGEST_FIRST_DNS_SUFFIXES = {
    ".amazonaws.com.cn",
    ".cloud.adc-e.uk",
    ".csp.hci.ic.gov",
    ".sc2s.sgov.gov",
    ".amazonaws.com",
    ".amazonaws.eu",
    ".c2s.ic.gov"
  };

  /**
   * O(1) check that the hostname ends with one of the known AWS partition DNS suffixes, ignoring
   * case and tolerating a trailing {@code .} and a well-formed {@code :port} (at most five ASCII
   * digits). Anchoring on the suffix (rather than scanning for a substring) also rejects hostnames
   * that merely contain such a suffix followed by other labels.
   *
   * <p>This is a conservative pre-filter, not an exact mirror of {@link #doParse}. A hostname with
   * a malformed or oversized port (for example {@code ...amazonaws.com:54x2} or {@code :543210}) is
   * rejected here even though {@code doParse} would truncate at the first {@code :} and could
   * otherwise accept the labels before it. Real RDS endpoints never carry such a suffix, so
   * rejecting them early is safe: the gate only ever keeps a superset of non-RDS hosts out of the
   * cache, never a valid RDS endpoint.
   */
  static boolean isPlausibleRdsHostname(final String hostname) {
    int end = hostname.length();
    if (end > 0 && hostname.charAt(end - 1) == '.') {
      end--;
    }
    end = stripPort(hostname, end);
    for (String suffix : DNS_SUFFIXES) {
      if (endsWithIgnoreCase(hostname, end, suffix)) {
        return true;
      }
    }
    return false;
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

    // Strip the partition DNS suffix so the remaining labels are the same shape in every
    // partition. The suffix is matched longest first so one suffix cannot be mistaken for a
    // shorter suffix.
    String prefix = null;
    for (String suffix : LONGEST_FIRST_DNS_SUFFIXES) {
      if (end >= suffix.length()
          && hostname.regionMatches(end - suffix.length(), suffix, 0, suffix.length())) {
        prefix = hostname.substring(0, end - suffix.length());
        break;
      }
    }
    if (prefix == null) {
      return NOT_RDS;
    }

    String[] labels = prefix.split("\\.", -1);

    // A user-defined RDS Proxy endpoint carries an extra "endpoint" label:
    // <name>.endpoint.proxy-<hash>.<region>.rds<suffix>. Drop that label so the proxy name and
    // Region are read by the same validated branches as the default proxy endpoint.
    if (labels.length > 3 && "endpoint".equals(labels[1]) && labels[2].startsWith("proxy-")) {
      String[] normalized = new String[labels.length - 1];
      normalized[0] = labels[0];
      System.arraycopy(labels, 2, normalized, 1, labels.length - 2);
      labels = normalized;
    }

    // After stripping the suffix an RDS endpoint is always four labels: the identifier, the hash,
    // and the Region and the literal "rds" in either order. Commercial, GovCloud and ISO endpoints
    // put the Region before "rds" (<id>.<hash>.<region>.rds), the China partition also uses
    // <id>.<hash>.rds.<region>, so accept both orders.
    if (labels.length != 4) {
      return NOT_RDS;
    }
    if ("rds".equals(labels[3])) {
      return build(labels[0], labels[1], labels[2]);
    }
    if ("rds".equals(labels[2])) {
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
