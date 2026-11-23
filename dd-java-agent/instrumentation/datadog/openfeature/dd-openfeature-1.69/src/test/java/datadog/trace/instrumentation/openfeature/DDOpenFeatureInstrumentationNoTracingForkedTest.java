package datadog.trace.instrumentation.openfeature;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "trace.enabled", value = "false")
class DDOpenFeatureInstrumentationNoTracingForkedTest extends DDOpenFeatureInstrumentationTest {}
