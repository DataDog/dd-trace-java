package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

@AutoService(Instrumenter.class)
public class JettyAddListenerInstrumentation extends Instrumenter.Tracing {
  public JettyAddListenerInstrumentation() {
    super("jetty-client");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.client.HttpRequest");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contexts = new HashMap<>(3);
    contexts.put(
        "org.eclipse.jetty.client.api.Request$RequestListener",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
    contexts.put(
        "org.eclipse.jetty.client.api.Response$ResponseListener",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
    return contexts;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("listener").or(nameStartsWith("on")))
            .and(takesArguments(1)),
        JettyAddListenerInstrumentation.class.getName() + "$AddSpanAdvice");
  }

  public static class AddSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(@Advice.Argument(0) final Object listener) {
      AgentSpan parent = activeSpan();
      if (parent == null) {
        parent = noopSpan();
      }

      if (listener instanceof Request.RequestListener) {
        InstrumentationContext.get(Request.RequestListener.class, AgentSpan.class)
            .put((Request.RequestListener) listener, parent);
      }

      if (listener instanceof Response.ResponseListener) {
        InstrumentationContext.get(Response.ResponseListener.class, AgentSpan.class)
            .put((Response.ResponseListener) listener, parent);
      }
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }
}
