package datadog.trace.instrumentation.mule4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;

/**
 * The {@code PrivilegedEvent} has a method that is called to set which {@code Event} is currently
 * being processed on this {@code Thread}. We activate/deactivate the span associated to that {@code
 * Event} via its {@code EventContext}.
 */
@AutoService(Instrumenter.class)
public final class PrivilegedEventInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public PrivilegedEventInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "org.mule.runtime.core.privileged.event.PrivilegedEvent";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.mule.runtime.api.event.EventContext",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setCurrentEvent"), packageName + ".PrivilegedEventSetCurrentAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CurrentEventHelper"};
  }
}
