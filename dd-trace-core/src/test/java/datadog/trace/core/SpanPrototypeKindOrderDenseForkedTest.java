package datadog.trace.core;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.test.junit.utils.config.WithConfig;

/**
 * {@link SpanPrototypeKindOrderForkedTest} with the dense store on, which walks a prototype's known
 * tags in slot order rather than insertion order.
 */
@WithConfig(key = TracerConfig.TRACE_DENSE_TAGS_ENABLED, value = "true")
class SpanPrototypeKindOrderDenseForkedTest extends SpanPrototypeKindOrderForkedTest {}
