package datadog.trace.instrumentation.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @ParameterizedTest(name = "{0} has account {1}")
  @CsvSource({
    "arn:aws:dynamodb:us-east-1:123456789012:table/orders, 123456789012",
    "arn:aws-cn:dynamodb:cn-north-1:210987654321:table/orders, 210987654321",
    "arn:aws:iam::123456789012:root, 123456789012",
    "arn:aws:sqs:us-east-1:123456789012:q, 123456789012"
  })
  void readsAccountFromArn(String arn, String expected) {
    assertEquals(expected, AwsAccountIdentity.arnAccount(arn));
  }

  @ParameterizedTest(name = "{0} has no account")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "orders",
        "arn:",
        "arn:aws",
        "arn:aws:dynamodb",
        "arn:aws:dynamodb:us-east-1",
        "arn:aws:dynamodb:us-east-1:123456789012",
        "arn:aws:s3:::bucket",
        "arn:aws:dynamodb:us-east-1::table/orders",
        "arn:aws:dynamodb:us-east-1:12345:table/orders",
        "arn:aws:dynamodb:us-east-1:1234567890123:table/orders",
        "arn:aws:dynamodb:us-east-1:12345678901a:table/orders",
        "ARN:aws:dynamodb:us-east-1:123456789012:table/orders"
      })
  void noAccountFromNonArnOrAccountlessArn(String value) {
    assertNull(AwsAccountIdentity.arnAccount(value));
  }
}
