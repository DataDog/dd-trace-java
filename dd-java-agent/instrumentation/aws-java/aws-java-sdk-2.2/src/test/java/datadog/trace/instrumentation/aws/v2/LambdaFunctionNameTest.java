package datadog.trace.instrumentation.aws.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/** Verifies that supported Lambda references normalize to the bare function name. */
class LambdaFunctionNameTest {

  @TableTest({
    "scenario                 | functionName                                                                                                                         | expectedName       ",
    "bare name                | my-function                                                                                                                          | my-function        ",
    "qualified name           | {'my-function:PROD', 'my-function:42'}                                                                                               | my-function        ",
    "partial ARN              | 123456789012:function:my-function                                                                                                    | my-function        ",
    "full ARN                 | arn:aws:lambda:us-east-1:123456789012:function:my-function                                                                           | my-function        ",
    "qualified full ARN       | {'arn:aws:lambda:us-east-1:123456789012:function:my-function:PROD', 'arn:aws:lambda:us-east-1:123456789012:function:my-function:42'} | my-function        ",
    "GovCloud ARN             | arn:aws-us-gov:lambda:us-gov-west-1:123456789012:function:my-function                                                                | my-function        ",
    "name containing function | my-function-handler:PROD                                                                                                             | my-function-handler",
    "colon only               | ':'                                                                                                                                  | ':'                ",
    "double colon only        | '::'                                                                                                                                 | '::'               "
  })
  void normalizesFunctionNameToBareName(String functionName, String expectedName) {
    assertEquals(expectedName, AwsSdkClientDecorator.lambdaFunctionName(functionName));
  }
}
