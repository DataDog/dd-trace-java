package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.grizzly.client.InjectAdapter.SETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class AsyncHttpClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public AsyncHttpClientInstrumentation() {
    super("grizzly-client", "ning");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.ning.http.client.AsyncHandler", Pair.class.getName());
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "com.ning.http.client.AsyncHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ClientDecorator", packageName + ".InjectAdapter"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("executeRequest")
            .and(takesArgument(0, named("com.ning.http.client.Request")))
            .and(takesArgument(1, named("com.ning.http.client.AsyncHandler")))
            .and(isPublic()),
        getClass().getName() + "$ExecuteRequest");
  }

  public static class ExecuteRequest {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final Request request,
        @Advice.Argument(1) final AsyncHandler<?> handler) {
      AgentSpan parentSpan = activeSpan();
      AgentSpan span = startSpan(HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      propagate().inject(span, request, SETTER);
      propagate()
          .injectPathwayContext(
              span, request, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
      InstrumentationContext.get(AsyncHandler.class, Pair.class)
          .put(handler, Pair.of(parentSpan, span));
    }
  }
}
