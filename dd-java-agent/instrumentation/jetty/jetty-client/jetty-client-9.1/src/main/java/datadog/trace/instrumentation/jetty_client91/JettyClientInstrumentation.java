package datadog.trace.instrumentation.jetty_client91;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.jetty_client.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.jetty_client91.JettyClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty_client91.JettyClientDecorator.HTTP_REQUEST;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

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
  public String muzzleDirective() {
    return "client";
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
        JettyClientInstrumentation.class.getName() + "$SendAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(RUNNABLE, singletonList("org.eclipse.jetty.util.SocketAddressResolver$1"));
  }

  public static class SendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan methodEnter(
        @Advice.Argument(0) Request request,
        @Advice.Argument(1) List<Response.ResponseListener> responseListeners) {
      AgentSpan span = startSpan("jetty-client", HTTP_REQUEST);
      InstrumentationContext.get(Request.class, AgentSpan.class).put(request, span);
      // make sure the span is finished before onComplete callbacks execute
      responseListeners.add(0, new SpanFinishingCompleteListener(span));
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      DECORATE.injectContext(getCurrentContext().with(span), request, SETTER);
      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentSpan span, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
