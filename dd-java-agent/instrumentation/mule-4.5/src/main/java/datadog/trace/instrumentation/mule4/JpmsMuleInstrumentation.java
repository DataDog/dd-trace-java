package datadog.trace.instrumentation.mule4;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.JavaModuleOpenProvider;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.Collection;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class JpmsMuleInstrumentation extends InstrumenterModule implements JavaModuleOpenProvider {
  public JpmsMuleInstrumentation() {
    super("mule", "mule-jpms");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true;
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      // added in 4.5.0
      new Reference.Builder("org.mule.runtime.tracer.api.EventTracer")
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
              "endCurrentSpan",
              "V",
              "Lorg/mule/runtime/api/event/Event;")
          .build(),
    };
  }

  @Override
  public Collection<String> triggerClasses() {
    return asList(
        "org.mule.runtime.tracer.customization.impl.info.ExecutionInitialSpanInfo",
        "org.mule.runtime.tracer.customization.impl.provider.LazyInitialSpanInfo");
  }
}
