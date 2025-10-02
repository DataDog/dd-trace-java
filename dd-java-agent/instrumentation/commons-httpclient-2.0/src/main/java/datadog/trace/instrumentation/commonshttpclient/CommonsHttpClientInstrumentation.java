package datadog.trace.instrumentation.commonshttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.commonshttpclient.HttpHeadersInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;

@AutoService(InstrumenterModule.class)
public class CommonsHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CommonsHttpClientInstrumentation() {
    super("commons-http-client");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.httpclient.HttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CommonsHttpClientDecorator", packageName + ".HttpHeadersInjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("executeMethod"))
            .and(takesArguments(3))
            .and(takesArgument(1, named("org.apache.commons.httpclient.HttpMethod"))),
        CommonsHttpClientInstrumentation.class.getName() + "$ExecAdvice");
  }

  public static class ExecAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(1) final HttpMethod httpMethod) {

      try {
        final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
        if (callDepth > 0) {
          return null;
        }

        final AgentSpan span = startSpan(HTTP_REQUEST);
        final AgentScope scope = activateSpan(span);

        DECORATE.afterStart(span);
        DECORATE.onRequest(span, httpMethod);
        DECORATE.injectContext(getCurrentContext().with(span), httpMethod, SETTER);

        return scope;
      } catch (BlockingException e) {
        CallDepthThreadLocalMap.reset(HttpClient.class);
        // re-throw blocking exceptions
        throw e;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(1) final HttpMethod httpMethod,
        @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      try {
        DECORATE.onResponse(span, httpMethod);
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
      } finally {
        scope.close();
        span.finish();
        CallDepthThreadLocalMap.reset(HttpClient.class);
      }
    }
  }
}
