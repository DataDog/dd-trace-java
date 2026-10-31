package datadog.trace.instrumentation.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AwsArnTest {

  @ParameterizedTest(name = "parses {0}")
  @CsvSource(
      nullValues = "NULL",
      value = {
        "arn:aws:dynamodb:us-east-1:123456789012:table/orders, aws, dynamodb, us-east-1, 123456789012, table/orders",
        "arn:aws:dynamodb:us-east-1:123456789012:table/orders/index/by-user, aws, dynamodb, us-east-1, 123456789012, table/orders/index/by-user",
        "arn:aws-cn:dynamodb:cn-north-1:123456789012:table/orders, aws-cn, dynamodb, cn-north-1, 123456789012, table/orders",
        "arn:aws-us-gov:sns:us-gov-west-1:123456789012:alerts, aws-us-gov, sns, us-gov-west-1, 123456789012, alerts",
        "arn:aws:s3:::my-bucket, aws, s3, NULL, NULL, my-bucket",
        "arn:aws:iam::123456789012:role/app, aws, iam, NULL, 123456789012, role/app",
        "arn:aws:states:us-east-1:123456789012:stateMachine:orders:extra:colons, aws, states, us-east-1, 123456789012, stateMachine:orders:extra:colons",
      })
  void parsesFields(
      String value,
      String partition,
      String service,
      String region,
      String account,
      String resource) {
    AwsArn arn = AwsArn.parse(value);
    assertNotNull(arn);
    assertEquals(value, arn.raw());
    assertEquals(partition, arn.partition());
    assertEquals(service, arn.service());
    assertEquals(region, arn.region());
    assertEquals(account, arn.account());
    assertEquals(resource, arn.resource());
  }

  @ParameterizedTest(name = "rejects {0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "orders",
        "arn:",
        "arn:aws",
        "arn:aws:dynamodb",
        "arn:aws:dynamodb:us-east-1",
        "arn:aws:dynamodb:us-east-1:123456789012",
        "arn:aws:dynamodb:us-east-1:123456789012:",
        "arn::dynamodb:us-east-1:123456789012:table/orders",
        "arn:aws::us-east-1:123456789012:table/orders",
        "table/orders",
        "https://sqs.us-east-1.amazonaws.com/123456789012/q",
      })
  void rejectsNonArn(String value) {
    assertNull(AwsArn.parse(value));
  }

  @ParameterizedTest(name = "account of {0} is null")
  @ValueSource(
      strings = {
        "arn:aws:dynamodb:us-east-1:12345:table/orders",
        "arn:aws:dynamodb:us-east-1:12345678901a:table/orders",
        "arn:aws:dynamodb:us-east-1::table/orders",
      })
  void accountMustBeTwelveDigits(String value) {
    AwsArn arn = AwsArn.parse(value);
    assertNotNull(arn);
    assertNull(arn.account());
  }
}
