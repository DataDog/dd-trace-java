package datadog.trace.instrumentation.tomcat9.executor;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "trace.thread-pool-executors.legacy.tracing.enabled", value = "true")
class TomcatExecutorLegacyForkedTest extends TomcatExecutorTest {}
