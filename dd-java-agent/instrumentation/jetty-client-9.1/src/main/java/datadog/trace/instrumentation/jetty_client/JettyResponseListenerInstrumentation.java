package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response.ResponseListener;

@AutoService(Instrumenter.class)
public class JettyResponseListenerInstrumentation extends Instrumenter.Tracing {
  public JettyResponseListenerInstrumentation() {
    super("jetty-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.eclipse.jetty.client.api.Response$ResponseListener");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("org.eclipse.jetty.client.api.Response$ResponseListener"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.eclipse.jetty.client.api.Response$ResponseListener",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "onBegin",
                    "onHeader",
                    "onHeaders",
                    "onContent",
                    "onSuccess",
                    "onFailure",
                    "onComplete")),
        JettyResponseListenerInstrumentation.class.getName() + "$ActivateSpanAdvice");
  }

  public static class ActivateSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.This final ResponseListener listener) {
      AgentSpan parent =
          InstrumentationContext.get(ResponseListener.class, AgentSpan.class).get(listener);
      return parent == null ? AgentTracer.NoopAgentScope.INSTANCE : activateSpan(parent);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      scope.close();
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }
}
