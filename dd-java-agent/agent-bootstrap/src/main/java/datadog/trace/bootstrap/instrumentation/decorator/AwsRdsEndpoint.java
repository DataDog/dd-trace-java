package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.KnownTags.AWS_RDS_ENDPOINT_TYPE;
import static datadog.trace.api.KnownTags.AWS_RDS_IDENTIFIER;
import static datadog.trace.api.KnownTags.AWS_REGION;
import static datadog.trace.api.KnownTags.DBCLUSTERIDENTIFIER;
import static datadog.trace.api.KnownTags.DBINSTANCEIDENTIFIER;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import java.util.Locale;

/**
 * Identity encoded in an Amazon RDS endpoint hostname.
 *
 * <p>RDS hands out DNS names of the form {@code <identifier>.<hash>.<region>.rds.amazonaws.com} (or
 * {@code <identifier>.<hash>.rds.<region>.amazonaws.com.cn} in the China partition). The leading
 * label is the DB instance identifier, the Aurora cluster identifier, a custom endpoint name, an
 * RDS Proxy name or proxy endpoint name, or an Aurora Limitless DB shard group name, depending on
 * the {@code <hash>} label prefix. Every regional endpoint carries its Region. An Aurora Global
 * Database writer endpoint, {@code <global-cluster-id>.global-<hash>.global.rds.amazonaws.com},
 * carries none because it follows the writer across Regions.
 *
 * <p>A cluster endpoint identifies the cluster but not the writer instance behind it, so this class
 * never claims an instance identifier for a cluster endpoint.
 *
 * <p>SQL client integrations parse the endpoint once with their connection metadata and apply it to
 * each span with {@link AgentSpan#setTagsFrom(TagContributor)}.
 */
public final class AwsRdsEndpoint implements TagContributor {

  public enum Type {
    /** {@code <db-instance-identifier>.<hash>.<region>.rds.amazonaws.com}. */
    INSTANCE("instance", DBINSTANCEIDENTIFIER),
    /** {@code <db-cluster-identifier>.cluster-<hash>.<region>.rds.amazonaws.com}. */
    CLUSTER("cluster", DBCLUSTERIDENTIFIER),
    /** {@code <db-cluster-identifier>.cluster-ro-<hash>.<region>.rds.amazonaws.com}. */
    CLUSTER_READER("cluster-ro", DBCLUSTERIDENTIFIER),
    /** {@code <endpoint-name>.cluster-custom-<hash>.<region>.rds.amazonaws.com}. */
    CLUSTER_CUSTOM("cluster-custom", AWS_RDS_IDENTIFIER),
    /**
     * {@code <db-cluster-identifier>.limitless-<hash>.<region>.rds.amazonaws.com}, the endpoint of
     * an Aurora Limitless Database DB cluster. Its leading label is the DB cluster identifier.
     */
    LIMITLESS("limitless", DBCLUSTERIDENTIFIER),
    /**
     * {@code <proxy-name>.proxy-<hash>.<region>.rds.amazonaws.com}, or a user-defined proxy
     * endpoint {@code <endpoint-name>.endpoint.proxy-<hash>.<region>.rds.amazonaws.com}.
     */
    PROXY("proxy", AWS_RDS_IDENTIFIER),
    /** {@code <shard-group-name>.shardgrp-<hash>.<region>.rds.amazonaws.com} (Aurora Limitless). */
    SHARD_GROUP("shardgrp", AWS_RDS_IDENTIFIER),
    /**
     * {@code <global-cluster-id>.global-<hash>.global.rds.amazonaws.com}, the Aurora Global
     * Database writer endpoint. The global cluster identifier is not a regional DB cluster
     * identifier.
     */
    GLOBAL_WRITER("global", AWS_RDS_IDENTIFIER);

    final String tagValue;
    // The one tag that carries the identifier. Instance and cluster endpoints use the same tag
    // names as the aws.rds.* integration metrics so spans join them; every other type uses
    // aws.rds.identifier. The value is never written under two keys.
    final long identifierTag;

    Type(final String tagValue, final long identifierTag) {
      this.tagValue = tagValue;
      this.identifierTag = identifierTag;
    }
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

  // Applications talk to a handful of databases; the cache only needs to absorb repeated parses of
  // the same few hostnames. Non-RDS results are cached too, as null values.
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
   * @return the identity encoded in the hostname, or {@code null} when RDS tagging is disabled or
   *     the hostname is not an RDS endpoint.
   */
  public static AwsRdsEndpoint parse(final CharSequence hostname) {
    if (!Config.get().isRdsTagsEnabled()
        || hostname == null
        || hostname.length() < MIN_DNS_SUFFIX_LENGTH) {
      return null;
    }
    String hostnameString = hostname.toString();
    // Every RDS endpoint ends in a known AWS partition DNS suffix. Reject everything else before
    // touching the cache, so database hosts outside AWS cannot evict RDS hostnames from it and
    // never pay for the label split.
    if (!isPlausibleRdsHostname(hostnameString)) {
      return null;
    }
    return CACHE.computeIfAbsent(hostnameString, AwsRdsEndpoint::doParse);
  }

  /**
   * Tags the span with the endpoint type, the Region when the hostname has one, and the identifier.
   * The identifier goes under {@code dbinstanceidentifier} for instance endpoints, {@code
   * dbclusteridentifier} for cluster, cluster reader and Aurora Limitless endpoints, and {@code
   * aws.rds.identifier} otherwise.
   */
  @Override
  public void addTo(final AgentSpan span) {
    span.setTag(type.identifierTag, identifier);
    span.setTag(AWS_RDS_ENDPOINT_TYPE, type.tagValue);
    if (region != null) {
      span.setTag(AWS_REGION, region);
    }
  }

  /**
   * The DB instance identifier, cluster identifier, custom endpoint name, proxy name, proxy
   * endpoint name or shard group name.
   */
  public String identifier() {
    return identifier;
  }

  public Type type() {
    return type;
  }

  /**
   * The AWS Region the endpoint lives in, for example {@code us-east-1}, or {@code null} for a
   * Global Database writer endpoint.
   */
  public String region() {
    return region;
  }

  /**
   * Checks that the hostname ends with one of the known AWS partition DNS suffixes, ignoring case
   * and tolerating a trailing {@code .} and a well-formed {@code :port} (one to five ASCII digits).
   * Anchoring on the suffix also rejects hostnames that only contain such a suffix followed by
   * other labels. {@link #doParse} uses the same host boundary and suffix match.
   */
  static boolean isPlausibleRdsHostname(final String hostname) {
    return suffixStart(hostname, hostEnd(hostname)) >= 0;
  }

  /**
   * Returns the end index of the host part, dropping a trailing {@code :port} and a trailing {@code
   * .} from a fully qualified name. The dot is tolerated on either side of the port, but only one
   * dot is dropped without a port, so {@code host..} is rejected.
   */
  private static int hostEnd(final String hostname) {
    int end = stripTrailingDot(hostname, hostname.length());
    int hostEnd = stripPort(hostname, end);
    return hostEnd < end ? stripTrailingDot(hostname, hostEnd) : hostEnd;
  }

  private static int stripTrailingDot(final String hostname, final int end) {
    return end > 0 && hostname.charAt(end - 1) == '.' ? end - 1 : end;
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

  /**
   * Returns where the partition DNS suffix starts in {@code hostname[0, end)}, ignoring case, or
   * {@code -1} when it ends in none of them.
   */
  private static int suffixStart(final String hostname, final int end) {
    // Every suffix ends in one of com, cn, gov, uk or eu. Checking that last letter first rejects
    // most other hostnames (.local, .internal, .corp, IP addresses) without trying each suffix.
    if (end == 0) {
      return -1;
    }
    switch (hostname.charAt(end - 1)) {
      case 'm':
      case 'M':
      case 'n':
      case 'N':
      case 'v':
      case 'V':
      case 'k':
      case 'K':
      case 'u':
      case 'U':
        break;
      default:
        return -1;
    }
    for (String suffix : DNS_SUFFIXES) {
      int start = end - suffix.length();
      if (start >= 0 && hostname.regionMatches(true, start, suffix, 0, suffix.length())) {
        return start;
      }
    }
    return -1;
  }

  private static boolean isAsciiDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  private static AwsRdsEndpoint doParse(final String rawHostname) {
    String hostname = rawHostname.toLowerCase(Locale.ROOT);
    int start = suffixStart(hostname, hostEnd(hostname));
    if (start < 0) {
      return null;
    }
    // Without the partition DNS suffix the remaining labels have the same shape in every partition.
    String prefix = hostname.substring(0, start);

    String[] labels = new String[5];
    int labelsCount = 0;
    {
      int labelStart = 0;
      int dot;
      while ((dot = prefix.indexOf('.', labelStart)) >= 0) {
        if (labelsCount == labels.length - 1) {
          return null;
        }
        labels[labelsCount++] = prefix.substring(labelStart, dot);
        labelStart = dot + 1;
      }
      labels[labelsCount++] = prefix.substring(labelStart);
    }

    // Custom proxy endpoints have an extra label before the proxy hash.
    // <name>.endpoint.proxy-<hash>.<region>.rds<suffix>
    int offset = 0;
    if (labelsCount > 3 && "endpoint".equals(labels[1]) && labels[2].startsWith("proxy-")) {
      offset = 1;
    }

    // Global Database writer: <id>.global-<hash>.global.rds<suffix>. No Region is encoded.
    if (offset == 0
        && labelsCount == 4
        && "global".equals(labels[2])
        && "rds".equals(labels[3])
        && labels[1].startsWith("global-")) {
      return labels[0].isEmpty() || !isHash(labels[1].substring("global-".length()))
          ? null
          : new AwsRdsEndpoint(labels[0], Type.GLOBAL_WRITER, null);
    }

    // The remaining labels are the identifier, hash, Region and literal "rds". The China
    // partition can put "rds" before the Region; other partitions put it after the Region.
    if (labelsCount != 4 + offset) {
      return null;
    }
    if ("rds".equals(labels[3 + offset])) {
      return build(labels[0], labels[1 + offset], labels[2 + offset]);
    }
    if ("rds".equals(labels[2 + offset])) {
      return build(labels[0], labels[1 + offset], labels[3 + offset]);
    }
    return null;
  }

  private static AwsRdsEndpoint build(
      final String identifier, final String hash, final String region) {
    if (identifier.isEmpty() || !isRegion(region)) {
      return null;
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
    } else if (hash.startsWith("limitless-")) {
      type = Type.LIMITLESS;
      suffix = hash.substring("limitless-".length());
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
      return null;
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
      if (!((c >= 'a' && c <= 'z') || isAsciiDigit(c))) {
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
      } else if (!((c >= 'a' && c <= 'z') || isAsciiDigit(c))) {
        return false;
      }
    }
    // Every Region ends in a digit and has at least two dashes (e.g. us-east-1).
    return dashes >= 2 && value.charAt(0) != '-' && isAsciiDigit(value.charAt(length - 1));
  }
}
