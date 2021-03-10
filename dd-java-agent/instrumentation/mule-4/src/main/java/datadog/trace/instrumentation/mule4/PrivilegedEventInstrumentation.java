package datadog.trace.instrumentation.mule4;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The {@code PrivilegedEvent} has a method that is called to set which {@code Event} is currently
 * being processed on this {@code Thread}. We activate/deactivate the span associated to that {@code
 * Event} via its {@code EventContext}.
 */
@AutoService(Instrumenter.class)
public final class PrivilegedEventInstrumentation extends Instrumenter.Tracing {

  public PrivilegedEventInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.mule.runtime.core.privileged.event.PrivilegedEvent");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.mule.runtime.api.event.EventContext",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(named("setCurrentEvent"), packageName + ".PrivilegedEventSetCurrentAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CurrentEventHelper"};
  }
}
