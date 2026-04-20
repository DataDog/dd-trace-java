package datadog.trace.instrumentation.hazelcast36;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

/**
 * Instrumentation module for Hazelcast 3.6 legacy support.
 *
 * <p>This module coordinates all Hazelcast 3.6 instrumentations and provides shared configuration.
 */
@AutoService(InstrumenterModule.class)
public final class HazelcastLegacyModule extends InstrumenterModule.Tracing {

  public HazelcastLegacyModule() {
    super("hazelcast_legacy");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HazelcastConstants",
      packageName + ".DistributedObjectDecorator",
      packageName + ".DistributedObjectDecorator$1",
      packageName + ".SpanFinishingExecutionCallback"
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ClientInvocationInstrumentation(), new DistributedObjectInstrumentation());
  }
}
