package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class FutureResponseListenerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public FutureResponseListenerInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.client.FutureResponseListener";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("org.eclipse.jetty.client.Request", AgentSpan.class.getName());
    contextStore.put(
        "org.eclipse.jetty.client.FutureResponseListener", "org.eclipse.jetty.client.Request");
    return contextStore;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(0, named("org.eclipse.jetty.client.Request")).and(takesArguments(2))),
        packageName + ".LinkListenerAdvice");
  }
}
