package datadog.trace.instrumentation.aws;

/**
 * A parsed Amazon Resource Name: {@code arn:<partition>:<service>:<region>:<account>:<resource>}.
 *
 * <p>Parsed once so callers that need several fields do not re-scan the string. The AWS SDK ships
 * its own parser ({@code software.amazon.awssdk.arns.Arn}) but it lives in a module that neither
 * the SDK v2 2.2.0 floor these instrumentations compile against nor SDK v1 provide, so a minimal
 * one is kept here.
 */
public final class AwsArn {

  private static final String TABLE_PREFIX = "table/";

  private final String raw;
  private final String partition;
  private final String service;
  private final String region;
  private final String account;
  private final String resource;

  private AwsArn(
      final String raw,
      final String partition,
      final String service,
      final String region,
      final String account,
      final String resource) {
    this.raw = raw;
    this.partition = partition;
    this.service = service;
    this.region = region;
    this.account = account;
    this.resource = resource;
  }

  /**
   * Parses an ARN.
   *
   * @return the parsed ARN, or {@code null} when the value is not of the form {@code
   *     arn:partition:service:region:account:resource} (partition and service non-empty).
   */
  public static AwsArn parse(final String value) {
    if (value == null || !value.startsWith("arn:")) {
      return null;
    }
    int c1 = value.indexOf(':', 4);
    if (c1 < 0) {
      return null;
    }
    int c2 = value.indexOf(':', c1 + 1);
    if (c2 < 0) {
      return null;
    }
    int c3 = value.indexOf(':', c2 + 1);
    if (c3 < 0) {
      return null;
    }
    int c4 = value.indexOf(':', c3 + 1);
    if (c4 < 0) {
      return null;
    }
    if (c1 == 4 || c2 == c1 + 1 || c4 == value.length() - 1) {
      // empty partition, empty service or empty resource
      return null;
    }
    return new AwsArn(
        value,
        value.substring(4, c1),
        value.substring(c1 + 1, c2),
        emptyToNull(value.substring(c2 + 1, c3)),
        emptyToNull(value.substring(c3 + 1, c4)),
        value.substring(c4 + 1));
  }

  /** The original string. */
  public String raw() {
    return raw;
  }

  /** {@code aws}, {@code aws-cn}, {@code aws-us-gov}, ... */
  public String partition() {
    return partition;
  }

  /** {@code dynamodb}, {@code sns}, {@code s3}, ... */
  public String service() {
    return service;
  }

  /** The Region, or {@code null} for global services (S3, IAM). */
  public String region() {
    return region;
  }

  /** The 12-digit account, or {@code null} when absent (S3 ARNs) or not exactly twelve digits. */
  public String account() {
    return AwsAccountIdentity.isAccountId(account) ? account : null;
  }

  /** Everything after the fifth colon. Never empty. */
  public String resource() {
    return resource;
  }

  /**
   * The table name of a DynamoDB table ARN, with any sub-resource ({@code /index/<name>}, {@code
   * /stream/<label>}, ...) removed.
   *
   * @return the bare table name, or {@code null} when the resource is not a table.
   */
  public String dynamoDbTableName() {
    if (!resource.startsWith(TABLE_PREFIX)) {
      return null;
    }
    int start = TABLE_PREFIX.length();
    int slash = resource.indexOf('/', start);
    String name = slash < 0 ? resource.substring(start) : resource.substring(start, slash);
    return name.isEmpty() ? null : name;
  }

  private static String emptyToNull(final String value) {
    return value.isEmpty() ? null : value;
  }
}
