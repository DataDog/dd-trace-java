package datadog.trace.instrumentation.hazelcast39;

import static datadog.trace.instrumentation.hazelcast39.HazelcastConstants.DEFAULT_ENABLED;
import static datadog.trace.instrumentation.hazelcast39.HazelcastConstants.INSTRUMENTATION_NAME;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instrumentation module for Hazelcast 3.9 support.
 *
 * <p>This module coordinates all Hazelcast 3.9 instrumentations and provides shared configuration.
 */
@AutoService(InstrumenterModule.class)
public final class HazelcastModule extends InstrumenterModule.Tracing {

  public HazelcastModule() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return DEFAULT_ENABLED;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ClientInvocationDecorator",
      packageName + ".SpanFinishingExecutionCallback",
      packageName + ".HazelcastConstants"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> stores = new HashMap<>();
    stores.put("com.hazelcast.client.impl.protocol.ClientMessage", String.class.getName());
    stores.put("com.hazelcast.client.spi.impl.ClientInvocation", String.class.getName());
    return stores;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(new ClientInvocationInstrumentation(), new ClientMessageInstrumentation());
  }
}
