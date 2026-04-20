package datadog.trace.instrumentation.opensearch;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.opensearch.OpensearchRestClientDecorator.DECORATE;
import static datadog.trace.instrumentation.opensearch.OpensearchRestClientDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseListener;

@AutoService(InstrumenterModule.class)
public class OpensearchRestClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public OpensearchRestClientInstrumentation() {
    super("opensearch", "opensearch-rest");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.opensearch.OpensearchRestClientDecorator",
      packageName + ".RestResponseListener",
    };
  }

  @Override
  public String instrumentedType() {
    return "org.opensearch.client.RestClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("performRequest"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.opensearch.client.Request"))),
        OpensearchRestClientInstrumentation.class.getName() + "$OpensearchRestClientAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("performRequestAsync"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.opensearch.client.Request")))
            .and(takesArgument(1, named("org.opensearch.client.ResponseListener"))),
        OpensearchRestClientInstrumentation.class.getName() + "$OpensearchRestClientAdvice");
  }

  public static class OpensearchRestClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Request request,
        @Advice.Argument(value = 1, readOnly = false, optional = true)
            ResponseListener responseListener) {

      final AgentSpan span = startSpan(OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onRequest(
          span,
          request.getMethod(),
          request.getEndpoint(),
          request.getEntity(),
          request.getParameters());

      if (responseListener != null) {
        responseListener = new RestResponseListener(responseListener, span);
      }

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object result) {
      if (throwable != null) {
        final AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      } else if (result instanceof Response) {
        final AgentSpan span = scope.span();
        if (((Response) result).getHost() != null) {
          DECORATE.onResponse(span, ((Response) result));
        }
        DECORATE.beforeFinish(span);
        span.finish();
      } else {
        // async call, span finished by RestResponseListener
      }
      scope.close();
    }
  }
}
