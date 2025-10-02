package datadog.trace.instrumentation.mule4;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Events in Mule have an {@code EventContext} attached to them, that travels with the event through
 * the system. We attach the active span to the concrete implementation of the {@code EventContext}
 * and activate/deactivate the span when mule changes which event it is processing.
 */
@AutoService(InstrumenterModule.class)
public final class EventContextInstrumentation extends AbstractMuleInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.mule.runtime.core.internal.event.DefaultEventContext",
      "org.mule.runtime.core.internal.event.DefaultEventContext$ChildEventContext"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".EventContextCreationAdvice");
  }

  @Override
  public String muzzleDirective() {
    return "before-4.5.0";
  }
}
