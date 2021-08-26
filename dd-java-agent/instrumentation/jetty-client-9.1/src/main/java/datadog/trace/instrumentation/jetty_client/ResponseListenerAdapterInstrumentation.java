package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

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
public final class ResponseListenerAdapterInstrumentation extends Instrumenter.Tracing {
  public ResponseListenerAdapterInstrumentation() {
    super("jetty-client");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.client.api.Response$Listener$Adapter");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("org.eclipse.jetty.client.api.Request", AgentSpan.class.getName());
    contextStore.put(
        "org.eclipse.jetty.client.api.Response$ResponseListener",
        "org.eclipse.jetty.client.api.Request");
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("onBegin", "onFailure"), getClass().getName() + "$Resume");
  }

  public static final class Resume {
    @Advice.OnMethodEnter
    public static void before(@Advice.This Response.ResponseListener listener) {
      // this will be populated whenever this is a FutureResponseListener, which means
      // this is the internal listener which does all the important work
      Request request =
          InstrumentationContext.get(Response.ResponseListener.class, Request.class).get(listener);
      if (null != request) {
        AgentSpan span = InstrumentationContext.get(Request.class, AgentSpan.class).get(request);
        if (null != span) {
          // This might get called twice on a thread for both onBegin and onFailure,
          // but there are other cases where only one or the other is called,
          // so we err on the side of extra invocations.
          span.finishThreadMigration();
        }
      }
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }
}
