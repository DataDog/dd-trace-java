package datadog.trace.instrumentation.ignite.v2;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.ignite.v2.cache.IgniteCacheAsyncInstrumentation;
import datadog.trace.instrumentation.ignite.v2.cache.IgniteCacheSyncInstrumentation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Instrumentation module for Apache Ignite 2.0 support.
 *
 * <p>This module coordinates all Ignite 2.0 tracing instrumentations and provides shared
 * configuration.
 */
@AutoService(InstrumenterModule.class)
public final class IgniteModule extends InstrumenterModule.Tracing {

  public IgniteModule() {
    super("ignite");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.apache.ignite.IgniteCache", "org.apache.ignite.Ignite");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.ignite.v2.cache.IgniteQueryInfo",
      "datadog.trace.instrumentation.ignite.v2.cache.IgniteQueryInfo$1",
      "datadog.trace.instrumentation.ignite.v2.cache.IgniteCacheDecorator",
      "datadog.trace.instrumentation.ignite.v2.cache.SpanFinishingCallback",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new IgniteInstrumentation(),
        new IgniteCacheSyncInstrumentation(),
        new IgniteCacheAsyncInstrumentation());
  }
}
