import datadog.trace.junit.utils.config.WithConfig;

@WithConfig(key = "trace.span.attribute.schema", value = "v1")
class LambdaHandlerInstrumentationV1ForkedTest extends LambdaHandlerInstrumentationTest {

  @Override
  int version() {
    return 1;
  }

  @Override
  String operation() {
    return "aws.lambda.invoke";
  }
}
