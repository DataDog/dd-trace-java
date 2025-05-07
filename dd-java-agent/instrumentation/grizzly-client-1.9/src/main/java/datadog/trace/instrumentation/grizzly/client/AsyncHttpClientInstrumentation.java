package datadog.trace.instrumentation.grizzly.client;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.grizzly.client.InjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class AsyncHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AsyncHttpClientInstrumentation() {
    super("grizzly-client", "ning");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singleton("mule"), false);
  }

  @Override
  public String instrumentedType() {
    return "com.ning.http.client.AsyncHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ClientDecorator",
      packageName + ".InjectAdapter",
      packageName + ".AsyncHandlerAdapter",
    };
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
        @Advice.Argument(value = 1, readOnly = false) AsyncHandler<?> handler) {
      AgentSpan parentSpan = activeSpan();
      AgentSpan span = startSpan(HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      defaultPropagator().inject(getCurrentContext().with(span), request, SETTER);
      handler = new AsyncHandlerAdapter<>(span, parentSpan, handler);
    }
  }
}
