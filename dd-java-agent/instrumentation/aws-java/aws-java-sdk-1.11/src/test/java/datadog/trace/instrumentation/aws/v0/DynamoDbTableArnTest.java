package datadog.trace.instrumentation.aws.v0;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/** Verifies that only a full DynamoDB table ARN counts as a table ARN. */
class DynamoDbTableArnTest {

  @TableTest({
    "scenario          | tableName                                                         | expected",
    "table ARN         | arn:aws:dynamodb:us-east-1:123456789012:table/orders              | true    ",
    "China table ARN   | arn:aws-cn:dynamodb:cn-north-1:123456789012:table/orders          | true    ",
    "GovCloud ARN      | arn:aws-us-gov:dynamodb:us-gov-west-1:123456789012:table/orders   | true    ",
    "table name        | orders                                                            | false   ",
    "index ARN         | arn:aws:dynamodb:us-east-1:123456789012:table/orders/index/byDate | false   ",
    "stream ARN        | arn:aws:dynamodb:us-east-1:123456789012:table/orders/stream/2024  | false   ",
    "other service ARN | arn:aws:sqs:us-east-1:123456789012:table/orders                   | false   ",
    "no account        | arn:aws:dynamodb:us-east-1::table/orders                          | false   ",
    "no region         | arn:aws:dynamodb::123456789012:table/orders                       | false   ",
    "no table name     | arn:aws:dynamodb:us-east-1:123456789012:table/                    | false   "
  })
  void recognizesOnlyFullTableArns(String tableName, boolean expected) {
    assertEquals(expected, AwsSdkClientDecorator.isTableArn(tableName));
  }
}
