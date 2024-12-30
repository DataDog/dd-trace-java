package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JettyHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {
  public JettyHttpClientInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.client.transport.HttpRequest";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyClientDecorator",
      packageName + ".HeadersInjectAdapter",
      packageName + ".SpanFinishingCompleteListener",
      packageName + ".CallbackWrapper",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.Request", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".RequestCreateAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.Response$CompleteListener"))),
        packageName + ".SendAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(namedOneOf("listener", "onSuccess", "onFailure", "onComplete"))
            .and(takesArguments(1)),
        packageName + ".WrapListenerAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(RUNNABLE, singletonList("org.eclipse.jetty.util.SocketAddressResolver$1"));
  }
}
