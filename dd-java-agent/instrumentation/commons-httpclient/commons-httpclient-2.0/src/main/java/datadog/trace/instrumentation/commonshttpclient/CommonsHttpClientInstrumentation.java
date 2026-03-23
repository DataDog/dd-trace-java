package datadog.trace.instrumentation.commonshttpclient;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.commonshttpclient.HeadersInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.commons.httpclient.HttpMethod;

@AutoService(InstrumenterModule.class)
public class CommonsHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CommonsHttpClientInstrumentation() {
    super("commons-httpclient");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.httpclient.HttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CommonsHttpClientDecorator", packageName + ".HeadersInjectAdapter"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // HttpClient has multiple executeMethod overloads
    // executeMethod(HttpMethod method)
    // executeMethod(HostConfiguration hostConfiguration, HttpMethod method)
    // executeMethod(HostConfiguration hostConfiguration, HttpMethod method, HttpState state)

    // Instrument the simple executeMethod(HttpMethod)
    transformer.applyAdvices(
        isMethod()
            .and(named("executeMethod"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.commons.httpclient.HttpMethod"))),
        CommonsHttpClientInstrumentation.class.getName() + "$ExecuteMethodAdvice",
        CommonsHttpClientInstrumentation.class.getName() + "$ContextPropagationAdvice");

    // Instrument executeMethod(HostConfiguration, HttpMethod)
    transformer.applyAdvices(
        isMethod()
            .and(named("executeMethod"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.commons.httpclient.HostConfiguration")))
            .and(takesArgument(1, named("org.apache.commons.httpclient.HttpMethod"))),
        CommonsHttpClientInstrumentation.class.getName() + "$ExecuteMethodAdvice",
        CommonsHttpClientInstrumentation.class.getName() + "$ContextPropagationAdvice");

    // Instrument executeMethod(HostConfiguration, HttpMethod, HttpState)
    transformer.applyAdvices(
        isMethod()
            .and(named("executeMethod"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.commons.httpclient.HostConfiguration")))
            .and(takesArgument(1, named("org.apache.commons.httpclient.HttpMethod")))
            .and(takesArgument(2, named("org.apache.commons.httpclient.HttpState"))),
        CommonsHttpClientInstrumentation.class.getName() + "$ExecuteMethodAdvice",
        CommonsHttpClientInstrumentation.class.getName() + "$ContextPropagationAdvice");
  }

  public static class ExecuteMethodAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(value = 0, optional = true) Object arg0,
        @Advice.Argument(value = 1, optional = true) Object arg1,
        @Advice.Local("inherited") AgentSpan inheritedSpan) {

      // Determine which argument is the HttpMethod
      // For executeMethod(HttpMethod), it's arg0
      // For executeMethod(HostConfiguration, HttpMethod) and
      // executeMethod(HostConfiguration, HttpMethod, HttpState), it's arg1
      final HttpMethod method;
      if (arg0 instanceof HttpMethod) {
        method = (HttpMethod) arg0;
      } else if (arg1 instanceof HttpMethod) {
        method = (HttpMethod) arg1;
      } else {
        return null;
      }

      AgentSpan activeSpan = activeSpan();
      // Detect if span was propagated here
      if (null != activeSpan) {
        // Reference equality to check this instrumentation created the span
        if (HTTP_REQUEST == activeSpan.getOperationName()) {
          inheritedSpan = activeSpan;
          return null;
        }
      }
      return activateSpan(DECORATE.prepareSpan(startSpan(HTTP_REQUEST), method));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter AgentScope scope,
        @Advice.Local("inherited") AgentSpan inheritedSpan,
        @Advice.Argument(value = 0, optional = true) Object arg0,
        @Advice.Argument(value = 1, optional = true) Object arg1,
        @Advice.Thrown final Throwable throwable) {
      try {
        // Determine which argument is the HttpMethod (same logic as enter)
        final HttpMethod method;
        if (arg0 instanceof HttpMethod) {
          method = (HttpMethod) arg0;
        } else if (arg1 instanceof HttpMethod) {
          method = (HttpMethod) arg1;
        } else {
          return;
        }

        AgentSpan span = scope != null ? scope.span() : inheritedSpan;
        if (span == null) {
          return;
        }

        DECORATE.onError(span, throwable);
        DECORATE.onResponse(span, method);

        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        if (scope != null) {
          scope.close();
        }
      }
    }
  }

  @AppliesOn(CONTEXT_TRACKING)
  public static class ContextPropagationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(value = 0, optional = true) Object arg0,
        @Advice.Argument(value = 1, optional = true) Object arg1) {

      // Determine which argument is the HttpMethod
      final HttpMethod method;
      if (arg0 instanceof HttpMethod) {
        method = (HttpMethod) arg0;
      } else if (arg1 instanceof HttpMethod) {
        method = (HttpMethod) arg1;
      } else {
        return;
      }

      DECORATE.injectContext(getCurrentContext(), method, SETTER);
    }
  }
}
