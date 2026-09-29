package datadog.trace.instrumentation.aws;

/**
 * Account identity of resources addressed by AWS SDK requests.
 *
 * <p>Two sources are trustworthy and used by the SDK instrumentations:
 *
 * <ul>
 *   <li>An ARN carried by the request itself (for example a DynamoDB {@code TableName} given as an
 *       ARN), parsed once with {@link AwsArn}.
 *   <li>The account that owns the credentials signing the request, when the SDK exposes it ({@code
 *       AwsCredentialsIdentity.accountId()} in AWS SDK for Java v2 2.26+, populated by the STS,
 *       SSO, profile, process and container credential providers). DynamoDB documents that a bare
 *       table name is resolved in the requestor's own account ("If you only provide the table name
 *       parameter instead of a complete ARN, the API operation will be performed on the table in
 *       the account to which the requestor belongs"), so for DynamoDB the caller account is the
 *       table owner.
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
}
