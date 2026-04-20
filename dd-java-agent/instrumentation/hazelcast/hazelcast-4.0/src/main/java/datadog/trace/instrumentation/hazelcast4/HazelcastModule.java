package datadog.trace.instrumentation.hazelcast4;

import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.DEFAULT_ENABLED;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.INSTRUMENTATION_NAME;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Instrumentation module for Hazelcast 4.0 support.
 *
 * <p>This module coordinates all Hazelcast 4.0 instrumentations and provides shared configuration.
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
      packageName + ".HazelcastConstants",
      packageName + ".HazelcastDecorator",
      packageName + ".SpanFinishingExecutionCallback",
      packageName + ".InvocationAdvice"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.hazelcast.client.impl.spi.impl.ClientInvocation", String.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ClientInvocationInstrumentation(), new ClientListenerInstrumentation());
  }
}
