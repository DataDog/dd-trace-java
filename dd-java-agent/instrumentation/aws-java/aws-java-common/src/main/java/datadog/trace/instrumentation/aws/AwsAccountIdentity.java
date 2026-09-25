package datadog.trace.instrumentation.aws;

/**
 * Derives AWS account identity for resources addressed by AWS SDK requests.
 *
 * <p>Two sources are trustworthy and used here:
 *
 * <ul>
 *   <li>An ARN carried by the request itself (for example a DynamoDB {@code TableName} given as an
 *       ARN, or an SNS {@code TopicArn}), parsed once with {@link AwsArn}.
 *   <li>The account that owns the credentials signing the request. Several AWS services resolve a
 *       bare resource name in the requestor's own account (DynamoDB documents this explicitly: "If
 *       you only provide the table name parameter instead of a complete ARN, the API operation will
 *       be performed on the table in the account to which the requestor belongs"), so for those
 *       services the caller account is the resource owner.
 * </ul>
 *
 * <p>The caller account is taken from the credentials object when the SDK exposes it ({@code
 * AwsCredentialsIdentity.accountId()} in AWS SDK for Java v2 2.26+, populated by the STS, SSO,
 * profile, process and container credential providers). As an opt-in fallback for older SDKs the
 * account can be decoded from the access key ID, which encodes it in its trailing characters.
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

  /** Maps a Region code to its partition. Unknown prefixes map to the commercial partition. */
  public static String partitionForRegion(final String region) {
    if (region == null) {
      return "aws";
    }
    if (region.startsWith("cn-")) {
      return "aws-cn";
    }
    if (region.startsWith("us-gov-")) {
      return "aws-us-gov";
    }
    if (region.startsWith("us-isob-")) {
      return "aws-iso-b";
    }
    if (region.startsWith("us-isof-")) {
      return "aws-iso-f";
    }
    if (region.startsWith("us-iso-")) {
      return "aws-iso";
    }
    if (region.startsWith("eu-isoe-")) {
      return "aws-iso-e";
    }
    if (region.startsWith("eusc-")) {
      return "aws-eusc";
    }
    return "aws";
  }

  /** Builds a DynamoDB table ARN, or returns {@code null} when the Region or account is unknown. */
  public static String dynamoDbTableArn(
      final String region, final String account, final String tableName) {
    if (region == null || !isAccountId(account) || tableName == null || tableName.isEmpty()) {
      return null;
    }
    return "arn:"
        + partitionForRegion(region)
        + ":dynamodb:"
        + region
        + ':'
        + account
        + ":table/"
        + tableName;
  }

  /**
   * Decodes the owning account from an AWS access key ID.
   *
   * <p>Access key IDs are a 4 character prefix ({@code AKIA} for long-term keys, {@code ASIA} for
   * temporary ones) followed by 16 base32 characters that encode 10 bytes. The account ID is held
   * in the first 48 bits of those bytes, shifted left by 7. This encoding is not part of the
   * documented AWS API surface, which is why callers only use it when explicitly enabled.
   *
   * @return the 12-digit account, or {@code null} when the key does not have the expected shape.
   */
  public static String accountFromAccessKeyId(final String accessKeyId) {
    if (accessKeyId == null || accessKeyId.length() != 20) {
      return null;
    }
    if (!(accessKeyId.startsWith("AKIA") || accessKeyId.startsWith("ASIA"))) {
      return null;
    }
    byte[] decoded = decodeBase32(accessKeyId, 4, 20);
    return decoded == null ? null : accountFromEncodedBytes(decoded);
  }

  /**
   * Decodes RFC 4648 base32 (no padding, case-insensitive) from {@code value[from, to)}.
   *
   * @return the decoded bytes, or {@code null} when a character is outside the alphabet.
   */
  static byte[] decodeBase32(final CharSequence value, final int from, final int to) {
    byte[] out = new byte[(to - from) * 5 / 8];
    int buffer = 0;
    int bits = 0;
    int index = 0;
    for (int i = from; i < to; i++) {
      int digit = base32Value(value.charAt(i));
      if (digit < 0) {
        return null;
      }
      buffer = (buffer << 5) | digit;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        out[index++] = (byte) (buffer >>> bits);
      }
    }
    return out;
  }

  /**
   * Extracts the account ID from the decoded bytes of an access key ID: the first 6 bytes hold
   * {@code account << 7}.
   *
   * @return the zero-padded 12-digit account, or {@code null} when fewer than 6 bytes are given or
   *     the value does not fit in 12 digits.
   */
  static String accountFromEncodedBytes(final byte[] decoded) {
    if (decoded.length < 6) {
      return null;
    }
    long firstSixBytes = 0;
    for (int i = 0; i < 6; i++) {
      firstSixBytes = (firstSixBytes << 8) | (decoded[i] & 0xff);
    }
    long account = (firstSixBytes & 0x7fffffffff80L) >>> 7;
    String text = Long.toString(account);
    if (text.length() > 12) {
      return null;
    }
    StringBuilder padded = new StringBuilder(12);
    for (int i = text.length(); i < 12; i++) {
      padded.append('0');
    }
    return padded.append(text).toString();
  }

  private static int base32Value(final char c) {
    if (c >= 'A' && c <= 'Z') {
      return c - 'A';
    }
    if (c >= 'a' && c <= 'z') {
      return c - 'a';
    }
    if (c >= '2' && c <= '7') {
      return c - '2' + 26;
    }
    return -1;
  }
}
