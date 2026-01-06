package datadog.trace.instrumentation.elasticsearch6_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.elasticsearch.ElasticsearchRestClientDecorator.DECORATE;
import static datadog.trace.instrumentation.elasticsearch.ElasticsearchRestClientDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseListener;

@AutoService(InstrumenterModule.class)
public class Elasticsearch6RestClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public Elasticsearch6RestClientInstrumentation() {
    super("elasticsearch", "elasticsearch-rest", "elasticsearch-rest-6");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.elasticsearch.ElasticsearchRestClientDecorator",
      packageName + ".RestResponseListener",
    };
  }

  @Override
  public String instrumentedType() {
    return "org.elasticsearch.client.RestClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("performRequestAsyncNoCatch"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.elasticsearch.client.Request")))
            .and(takesArgument(1, named("org.elasticsearch.client.ResponseListener"))),
        Elasticsearch6RestClientInstrumentation.class.getName() + "$ElasticsearchRestClientAdvice");
  }

  public static class ElasticsearchRestClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Request request,
        @Advice.Argument(value = 1, readOnly = false) ResponseListener responseListener) {

      final AgentSpan span = startSpan(OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onRequest(
          span,
          request.getMethod(),
          request.getEndpoint(),
          request.getEntity(),
          request.getParameters());

      responseListener = new RestResponseListener(responseListener, span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
      scope.close();
      // span finished by RestResponseListener
    }
  }
}
