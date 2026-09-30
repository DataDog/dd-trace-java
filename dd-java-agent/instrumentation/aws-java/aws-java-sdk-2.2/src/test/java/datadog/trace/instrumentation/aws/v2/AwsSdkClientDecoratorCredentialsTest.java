package datadog.trace.instrumentation.aws.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;

/**
 * {@code AwsCredentialsIdentity.accountId()} does not exist in the SDK this suite compiles against,
 * so the lookup is exercised with credentials classes that declare the same method.
 */
public class AwsSdkClientDecoratorCredentialsTest extends AbstractInstrumentationTest {

  @Test
  void readsTheAccountFromCredentialsThatExposeIt() {
    assertEquals(
        "123456789012",
        AwsSdkClientDecorator.credentialsAccountId(new AccountCredentials("123456789012")));
  }

  @Test
  void ignoresCredentialsWithoutAnAccountIdMethod() {
    // AwsBasicCredentials at 2.2.0 predates accountId()
    assertNull(
        AwsSdkClientDecorator.credentialsAccountId(AwsBasicCredentials.create("key", "secret")));
  }

  @Test
  void ignoresAnEmptyOrMalformedAccount() {
    assertNull(AwsSdkClientDecorator.credentialsAccountId(new AccountCredentials(null)));
    assertNull(AwsSdkClientDecorator.credentialsAccountId(new AccountCredentials("12345")));
    assertNull(
        AwsSdkClientDecorator.credentialsAccountId(new AccountCredentials("arn:aws:iam::1:root")));
  }

  @Test
  void treatsAFailingLookupAsAbsent() {
    assertNull(AwsSdkClientDecorator.credentialsAccountId(new FailingCredentials()));
  }

  public static final class AccountCredentials implements AwsCredentials {
    private final String account;

    AccountCredentials(String account) {
      this.account = account;
    }

    public Optional<String> accountId() {
      return Optional.ofNullable(account);
    }

    @Override
    public String accessKeyId() {
      return "key";
    }

    @Override
    public String secretAccessKey() {
      return "secret";
    }
  }

  public static final class FailingCredentials implements AwsCredentials {
    public Optional<String> accountId() {
      throw new IllegalStateException("provider could not resolve the account");
    }

    @Override
    public String accessKeyId() {
      return "key";
    }

    @Override
    public String secretAccessKey() {
      return "secret";
    }
  }
}
