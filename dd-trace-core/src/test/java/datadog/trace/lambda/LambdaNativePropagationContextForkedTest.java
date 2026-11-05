package datadog.trace.lambda;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class LambdaNativePropagationContextForkedTest extends LambdaNativePropagationTest {}
