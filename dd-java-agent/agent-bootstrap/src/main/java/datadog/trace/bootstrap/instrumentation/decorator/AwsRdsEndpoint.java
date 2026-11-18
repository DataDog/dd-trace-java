package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.Locale;

/**
 * Identity encoded in an Amazon RDS endpoint hostname.
 *
 * <p>RDS hands out DNS names of the form {@code <identifier>.<hash>.<region>.rds.amazonaws.com} (or
 * {@code <identifier>.<hash>.rds.<region>.amazonaws.com.cn} in the China partition). The leading
 * label is the DB instance identifier, the Aurora cluster identifier, a custom endpoint name, an
 * RDS Proxy name or an Aurora Limitless DB shard group name depending on the {@code <hash>} label
 * prefix. The Region is always present.
 *
 * <p>A cluster endpoint identifies the cluster but not the writer instance behind it, so this class
 * never claims an instance identifier for a cluster endpoint.
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
    PROXY("proxy"),
    /** {@code <shard-group-name>.shardgrp-<hash>.<region>.rds.amazonaws.com} (Aurora Limitless). */
    SHARD_GROUP("shardgrp");

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
    if (hostname == null || hostname.length() < MIN_DNS_SUFFIX_LENGTH) {
      return null;
    }
    String hostnameString = hostname.toString();
    // Every RDS endpoint ends in a known AWS partition DNS suffix. Reject everything else before
    // touching the cache, so database hosts outside AWS cannot evict RDS hostnames from it and
    // never pay for the label split.
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

  // Cheap length floor checked before any other work.
  private static final int MIN_DNS_SUFFIX_LENGTH;

  static {
    int minimum = Integer.MAX_VALUE;
    for (String suffix : DNS_SUFFIXES) {
      minimum = Math.min(minimum, suffix.length());
    }
    MIN_DNS_SUFFIX_LENGTH = minimum;
  }

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
   * rejecting them early is safe: the gate never rejects a valid RDS endpoint.
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

  /**
   * The DB instance identifier, cluster identifier, custom endpoint name, proxy name or shard group
   * name.
   */
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
    // partition.
    String prefix = null;
    for (String suffix : DNS_SUFFIXES) {
      if (end >= suffix.length()
          && hostname.regionMatches(end - suffix.length(), suffix, 0, suffix.length())) {
        prefix = hostname.substring(0, end - suffix.length());
        break;
      }
    }
    if (prefix == null) {
      return NOT_RDS;
    }

    String[] labels = new String[5];
    int labelsCount = 0;
    {
      int start = 0;
      int dot;
      while ((dot = prefix.indexOf(".", start)) >= 0) {
        if (labelsCount == labels.length - 1) {
          return NOT_RDS;
        }
        labels[labelsCount++] = prefix.substring(start, dot);
        start = dot + 1;
      }
      labels[labelsCount++] = prefix.substring(start);
    }

    // Custom proxy endpoints have an extra label before the proxy hash.
    // <name>.endpoint.proxy-<hash>.<region>.rds<suffix>
    int offset = 0;
    if (labelsCount > 3 && "endpoint".equals(labels[1]) && labels[2].startsWith("proxy-")) {
      offset = 1;
    }

    // The remaining labels are the identifier, hash, Region and literal "rds". The China
    // partition can put "rds" before the Region; other partitions put it after the Region.
    if (labelsCount != 4 + offset) {
      return NOT_RDS;
    }
    if ("rds".equals(labels[3 + offset])) {
      return build(labels[0], labels[1 + offset], labels[2 + offset]);
    }
    if ("rds".equals(labels[2 + offset])) {
      return build(labels[0], labels[1 + offset], labels[3 + offset]);
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
    } else if (hash.startsWith("shardgrp-")) {
      type = Type.SHARD_GROUP;
      suffix = hash.substring("shardgrp-".length());
    } else {
      type = Type.INSTANCE;
      suffix = hash;
    }
    if (!isHash(suffix)) {
      return NOT_RDS;
    }
    return new AwsRdsEndpoint(identifier, type, region);
  }

  /**
   * The account and Region specific label RDS adds to every endpoint: lowercase alphanumerics only.
   */
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
