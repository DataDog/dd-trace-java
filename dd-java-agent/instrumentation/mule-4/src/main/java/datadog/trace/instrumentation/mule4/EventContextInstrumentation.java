package datadog.trace.instrumentation.mule4;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Events in Mule have an {@code EventContext} attached to them, that travels with
 * the event through the system. We attach the active span to the concrete implementation
 * of the {@code EventContext} and activate/deactivate the span when mule changes which
 * event it is processing.
 */
@AutoService(Instrumenter.class)
public final class EventContextInstrumentation extends Instrumenter.Tracing {

  public EventContextInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(
        "org.mule.runtime.core.internal.event.DefaultEventContext",
        "org.mule.runtime.core.internal.event.DefaultEventContext$ChildEventContext");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.mule.runtime.api.event.EventContext",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(isConstructor(), packageName + ".EventContextCreationAdvice");
  }
}
