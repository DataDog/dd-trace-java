package datadog.trace.instrumentation.jetty_client10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JettyClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {
  public JettyClientInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.client.HttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyClientDecorator",
      "datadog.trace.instrumentation.jetty_client.HeadersInjectAdapter",
      "datadog.trace.instrumentation.jetty_client.CallbackWrapper",
      packageName + ".SpanFinishingCompleteListener"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.api.Request", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(
                takesArgument(
                    0,
                    namedOneOf(
                        "org.eclipse.jetty.client.api.Request",
                        "org.eclipse.jetty.client.HttpRequest")))
            .and(takesArgument(1, List.class)),
        packageName + ".SendAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(RUNNABLE, singletonList("org.eclipse.jetty.util.SocketAddressResolver$1"));
  }
}
