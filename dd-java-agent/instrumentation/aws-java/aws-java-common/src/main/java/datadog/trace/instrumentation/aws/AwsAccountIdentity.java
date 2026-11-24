package datadog.trace.instrumentation.aws;

/**
 * Account identity of resources addressed by AWS SDK requests.
 *
 * <p>The {@code aws_account} span tag always identifies the owner of the addressed resource, not
 * the caller in general. The account that owns the signing credentials may be used only when the
 * service contract guarantees that it also owns the addressed resource.
 *
 * <p>Two sources are trustworthy and used by the SDK instrumentations:
 *
 * <ul>
 *   <li>An ARN carried by the request itself (for example a DynamoDB {@code TableName} given as an
 *       ARN), read with {@link #arnAccount(String)}.
 *   <li>The account that owns the credentials signing the request, when the SDK exposes it ({@code
 *       AwsCredentialsIdentity.accountId()} in AWS SDK for Java v2 2.26+), but only for a bare
 *       DynamoDB table name. DynamoDB guarantees that a bare name is resolved in the requestor's
 *       own account, so the credential owner and table owner are the same. This source must not be
 *       reused for services that can address resources owned by another account.
 * </ul>
 */
public final class AwsAccountIdentity {

  private AwsAccountIdentity() {}

  /** {@code true} for exactly twelve ASCII digits. */
  public static boolean isAccountId(final String value) {
    if (value == null || value.length() != 12) {
      return false;
    }
    for (int i = 0; i < 12; i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * The account of an ARN, {@code arn:<partition>:<service>:<region>:<account>:<resource>}.
   *
   * @return the account when the fifth field is exactly twelve digits, otherwise {@code null} (not
   *     an ARN, or an ARN without an account such as an S3 bucket ARN).
   */
  public static String arnAccount(final String arn) {
    if (arn == null || !arn.startsWith("arn:")) {
      return null;
    }
    int end = arn.length();
    int from = 4;
    // skip partition, service and Region to reach the account field
    for (int i = 0; i < 3; i++) {
      from = arn.indexOf(':', from) + 1;
      if (from == 0) {
        return null;
      }
    }
    int to = from + 12;
    if (to >= end || arn.charAt(to) != ':') {
      return null;
    }
    for (int i = from; i < to; i++) {
      char c = arn.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
    }
    return arn.substring(from, to);
  }
}
