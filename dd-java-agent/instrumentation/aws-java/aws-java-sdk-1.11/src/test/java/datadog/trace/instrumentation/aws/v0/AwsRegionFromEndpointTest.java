package datadog.trace.instrumentation.aws.v0;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/** Verifies that the Region is read only from standard regional AWS endpoint hosts. */
class AwsRegionFromEndpointTest {

  @TableTest({
    "scenario              | host                                 | expectedRegion",
    "regional endpoint     | sqs.us-west-2.amazonaws.com          | us-west-2     ",
    "upper case host       | SQS.US-WEST-2.AMAZONAWS.COM          | us-west-2     ",
    "GovCloud endpoint     | dynamodb.us-gov-west-1.amazonaws.com | us-gov-west-1 ",
    "China endpoint        | sqs.cn-north-1.amazonaws.com.cn      | cn-north-1    ",
    "FIPS endpoint         | sqs-fips.us-east-1.amazonaws.com     | us-east-1     ",
    "dual-stack endpoint   | s3.dualstack.eu-west-1.amazonaws.com | eu-west-1     ",
    "virtual-hosted bucket | bucket.s3.ap-south-1.amazonaws.com   | ap-south-1    ",
    "global endpoint       | s3.amazonaws.com                     |               ",
    "legacy dash form      | s3-us-west-2.amazonaws.com           |               ",
    "no service label      | us-west-2.amazonaws.com              |               ",
    "custom endpoint       | localhost                            |               ",
    "lookalike domain      | sqs.us-west-2.amazonaws.com.evil.com |               ",
    "no host               |                                      |               "
  })
  void extractsRegionFromStandardEndpoints(String host, String expectedRegion) {
    assertEquals(expectedRegion, AwsSdkClientDecorator.regionFromEndpointHost(host));
  }
}
