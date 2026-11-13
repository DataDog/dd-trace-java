package datadog.trace.instrumentation.mongo;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
@WithConfig(key = "dbm.propagation.mode", value = "full")
class ContextSwapAsyncMongoDbmForkedTest extends AsyncMongoDbmTest {}
