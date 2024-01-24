package datadog.trace.instrumentation.mule4;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;

/**
 * Events in Mule have an {@code EventContext} attached to them, that travels with the event through
 * the system. We attach the active span to the concrete implementation of the {@code EventContext}
 * and activate/deactivate the span when mule changes which event it is processing.
 */
@AutoService(Instrumenter.class)
public final class EventContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public EventContextInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.mule.runtime.core.internal.event.DefaultEventContext",
      "org.mule.runtime.core.internal.event.DefaultEventContext$ChildEventContext"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.mule.runtime.api.event.EventContext",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".EventContextCreationAdvice");
  }
}
