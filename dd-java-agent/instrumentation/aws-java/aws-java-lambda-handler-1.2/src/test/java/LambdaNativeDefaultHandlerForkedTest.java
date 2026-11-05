import datadog.trace.test.junit.utils.config.WithConfig;

/** Empty configuration uses the native default, including nested and exceptional handlers. */
@WithConfig(key = "universal.instrumentation", value = "")
class LambdaNativeDefaultHandlerForkedTest extends LambdaNativeHandlerForkedTest {}
