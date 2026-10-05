import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "trace.span.attribute.schema", value = "v0")
class LambdaHandlerInstrumentationV0Test extends LambdaHandlerInstrumentationTest {

  @Override
  int version() {
    return 0;
  }

  @Override
  String operation() {
    return "dd-tracer-serverless-span";
  }
}
