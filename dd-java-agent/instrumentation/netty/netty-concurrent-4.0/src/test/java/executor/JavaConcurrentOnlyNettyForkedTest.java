package executor;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "integrations.enabled", value = "false")
@WithConfig(key = "integration.java_concurrent.enabled", value = "true")
@WithConfig(key = "integration.trace-annotation.enabled", value = "true")
class JavaConcurrentOnlyNettyForkedTest
    extends GrpcShadedNettyScheduledFutureTaskContextPropagationTest {}
