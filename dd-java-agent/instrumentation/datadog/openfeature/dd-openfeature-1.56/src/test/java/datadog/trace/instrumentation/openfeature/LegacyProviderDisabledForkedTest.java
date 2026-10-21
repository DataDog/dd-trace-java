package datadog.trace.instrumentation.openfeature;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "feature.flags.enabled", value = "false")
class LegacyProviderDisabledForkedTest extends LegacyProviderInstrumentationTest {}
