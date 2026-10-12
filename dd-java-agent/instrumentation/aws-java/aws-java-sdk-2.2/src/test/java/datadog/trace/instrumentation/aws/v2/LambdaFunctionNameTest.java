package datadog.trace.instrumentation.aws.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Verifies that every accepted AWS Lambda {@code FunctionName} form normalizes to the bare function
 * name for {@code aws.function.name}, so the same logical function is not split across tag values.
 */
class LambdaFunctionNameTest {

  @ParameterizedTest
  @CsvSource({
    // bare name
    "my-function, my-function",
    // name with an alias qualifier
    "my-function:PROD, my-function",
    // name with a numeric version qualifier
    "my-function:42, my-function",
    // partial ARN (account:function:name)
    "123456789012:function:my-function, my-function",
    // full ARN
    "arn:aws:lambda:us-east-1:123456789012:function:my-function, my-function",
    // full ARN with an alias qualifier
    "arn:aws:lambda:us-east-1:123456789012:function:my-function:PROD, my-function",
    // full ARN with a numeric version qualifier
    "arn:aws:lambda:us-east-1:123456789012:function:my-function:42, my-function",
    // GovCloud partition full ARN
    "arn:aws-us-gov:lambda:us-gov-west-1:123456789012:function:my-function, my-function",
    // bare name that literally contains 'function', with a qualifier (regression)
    "my-function-handler:PROD, my-function-handler",
  })
  void normalizesFunctionNameToBareName(String functionName, String expectedName) {
    assertEquals(expectedName, AwsSdkClientDecorator.lambdaFunctionName(functionName));
  }
}
