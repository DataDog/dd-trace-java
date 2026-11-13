package datadog.trace.instrumentation.mongo;

import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE;
import static datadog.trace.api.config.TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = LEGACY_CONTEXT_MANAGER_ENABLED, value = "false")
@WithConfig(key = DB_DBM_PROPAGATION_MODE_MODE, value = "full")
class ContextSwapAsyncMongoDbmForkedTest extends AsyncMongoDbmTest {}
