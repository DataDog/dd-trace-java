package datadog.trace.instrumentation.aws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AwsAccountIdentityTest {

  @ParameterizedTest(name = "{0} is an account id")
  @ValueSource(strings = {"123456789012", "000000000000", "999999999999"})
  void acceptsTwelveDigits(String value) {
    assertTrue(AwsAccountIdentity.isAccountId(value));
  }

  @ParameterizedTest(name = "{0} is not an account id")
  @NullAndEmptySource
  @ValueSource(
      strings = {"12345", "1234567890123", "12345678901a", " 23456789012", "arn:aws:s3:::b"})
  void rejectsNonAccountIds(String value) {
    assertFalse(AwsAccountIdentity.isAccountId(value));
  }

  @ParameterizedTest(name = "{0} is in partition {1}")
  @CsvSource(
      nullValues = "NULL",
      value = {
        "us-east-1, aws",
        "eu-central-1, aws",
        "cn-north-1, aws-cn",
        "us-gov-west-1, aws-us-gov",
        "us-iso-east-1, aws-iso",
        "us-isob-east-1, aws-iso-b",
        "eu-isoe-west-1, aws-iso-e",
        "us-isof-south-1, aws-iso-f",
        "eusc-de-east-1, aws-eusc",
        "NULL, aws",
      })
  void mapsRegionToPartition(String region, String partition) {
    assertEquals(partition, AwsAccountIdentity.partitionForRegion(region));
  }

  @Test
  void buildsDynamoDbTableArn() {
    assertEquals(
        "arn:aws:dynamodb:us-west-2:123456789012:table/orders",
        AwsAccountIdentity.dynamoDbTableArn("us-west-2", "123456789012", "orders"));
    assertEquals(
        "arn:aws-cn:dynamodb:cn-north-1:123456789012:table/orders",
        AwsAccountIdentity.dynamoDbTableArn("cn-north-1", "123456789012", "orders"));
    assertNull(AwsAccountIdentity.dynamoDbTableArn(null, "123456789012", "orders"));
    assertNull(AwsAccountIdentity.dynamoDbTableArn("us-west-2", null, "orders"));
    assertNull(AwsAccountIdentity.dynamoDbTableArn("us-west-2", "1234", "orders"));
    assertNull(AwsAccountIdentity.dynamoDbTableArn("us-west-2", "123456789012", ""));
  }

  @Test
  void decodesAccountFromAccessKeyId() {
    // Key IDs built by encoding known accounts with the documented layout: 4 char prefix, then
    // base32 of 10 bytes whose first 48 bits are (account << 7) plus arbitrary low bits.
    assertEquals(
        "123456789012",
        AwsAccountIdentity.accountFromAccessKeyId(syntheticKey("AKIA", 123456789012L)));
    assertEquals(
        "640168429175",
        AwsAccountIdentity.accountFromAccessKeyId(syntheticKey("ASIA", 640168429175L)));
    assertEquals(
        "000000000001", AwsAccountIdentity.accountFromAccessKeyId(syntheticKey("ASIA", 1L)));
    assertEquals(
        "999999999999",
        AwsAccountIdentity.accountFromAccessKeyId(syntheticKey("AKIA", 999999999999L)));
  }

  @ParameterizedTest(name = "rejects access key id {0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "my-access-key",
        "AKIA",
        // 19 chars
        "AKIAIOSFODNN7EXAMPL",
        // 21 chars
        "AKIAIOSFODNN7EXAMPLEX",
        // unknown prefix
        "ABCDIOSFODNN7EXAMPLE",
        // '1' is not base32
        "AKIAIOSFODNN7EXAMPL1",
        // '0' is not base32 (and wrong case prefix)
        "akiaiosfodnn7example0",
      })
  void rejectsMalformedAccessKeyIds(String value) {
    assertNull(AwsAccountIdentity.accountFromAccessKeyId(value));
  }

  @Test
  void decodesRfc4648Base32() {
    // RFC 4648 test vector: "foobar" -> MZXW6YTBOI (unpadded)
    assertArrayEquals(
        "foobar".getBytes(StandardCharsets.US_ASCII),
        AwsAccountIdentity.decodeBase32("MZXW6YTBOI", 0, 10));
    // lower case alphabet is accepted
    assertArrayEquals(
        "foobar".getBytes(StandardCharsets.US_ASCII),
        AwsAccountIdentity.decodeBase32("mzxw6ytboi", 0, 10));
    // sub-range decoding, as used to skip the access key prefix
    assertArrayEquals(
        "foobar".getBytes(StandardCharsets.US_ASCII),
        AwsAccountIdentity.decodeBase32("AKIAMZXW6YTBOI", 4, 14));
  }

  @ParameterizedTest(name = "base32 rejects {0}")
  @ValueSource(strings = {"MZXW6YTBO1", "MZXW6YTBO0", "MZXW6YTBO8", "MZXW6YTBO=", "MZXW6YTBO-"})
  void base32RejectsCharactersOutsideTheAlphabet(String value) {
    assertNull(AwsAccountIdentity.decodeBase32(value, 0, value.length()));
  }

  @Test
  void extractsAccountFromEncodedBytes() {
    // (123456789012 << 7) | 0x55 = 0x0E5F_4C8D_0A55 big-endian over 6 bytes; the low 7 bits and
    // the trailing bytes are not part of the account and are ignored.
    byte[] bytes = {0x0E, 0x5F, 0x4C, (byte) 0x8D, 0x0A, 0x55, 0x12, 0x34, 0x56, 0x78};
    assertEquals("123456789012", AwsAccountIdentity.accountFromEncodedBytes(bytes));
    byte[] one = {0, 0, 0, 0, 0, (byte) 0x80};
    assertEquals("000000000001", AwsAccountIdentity.accountFromEncodedBytes(one));
    assertNull(AwsAccountIdentity.accountFromEncodedBytes(new byte[5]));
  }

  private static String syntheticKey(String prefix, long account) {
    long firstSixBytes = (account << 7) | 0x55L; // low 7 bits are not part of the account
    byte[] raw = new byte[10];
    for (int i = 5; i >= 0; i--) {
      raw[i] = (byte) (firstSixBytes & 0xff);
      firstSixBytes >>>= 8;
    }
    raw[6] = (byte) 0x12;
    raw[7] = (byte) 0x34;
    raw[8] = (byte) 0x56;
    raw[9] = (byte) 0x78;
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    StringBuilder out = new StringBuilder(prefix);
    int buffer = 0;
    int bits = 0;
    for (byte b : raw) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(alphabet.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    assertEquals(20, out.length());
    return out.toString();
  }
}
