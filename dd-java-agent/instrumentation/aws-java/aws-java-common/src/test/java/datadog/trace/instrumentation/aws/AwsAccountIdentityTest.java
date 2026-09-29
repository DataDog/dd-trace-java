package datadog.trace.instrumentation.aws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
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
}
