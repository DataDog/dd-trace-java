package datadog.trace.instrumentation.elasticsearch7_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.elasticsearch.ElasticsearchTransportClientDecorator.DECORATE;
import static datadog.trace.instrumentation.elasticsearch.ElasticsearchTransportClientDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;

/** This class is identical to version 6's instrumentation, except Action is now ActionType. */
@AutoService(Instrumenter.class)
public class Elasticsearch73TransportClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public Elasticsearch73TransportClientInstrumentation() {
    super("elasticsearch", "elasticsearch-transport", "elasticsearch-transport-7");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Avoid matching pre-ES7 releases which have their own instrumentations.
    return hasClassNamed("org.elasticsearch.action.ActionType");
  }

  @Override
  public String instrumentedType() {
    // If we want to be more generic, we could instrument the interface instead:
    // .and(safeHasSuperType(named("org.elasticsearch.client.ElasticsearchClient"))))
    return "org.elasticsearch.client.support.AbstractClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.elasticsearch.ElasticsearchTransportClientDecorator",
      packageName + ".TransportActionListener",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.elasticsearch.action.ActionType")))
            .and(takesArgument(1, named("org.elasticsearch.action.ActionRequest")))
            .and(takesArgument(2, named("org.elasticsearch.action.ActionListener"))),
        Elasticsearch73TransportClientInstrumentation.class.getName()
            + "$Elasticsearch73TransportClientAdvice");
  }

  public static class Elasticsearch73TransportClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final ActionType action,
        @Advice.Argument(1) final ActionRequest actionRequest,
        @Advice.Argument(value = 2, readOnly = false)
            ActionListener<ActionResponse> actionListener) {

      final AgentSpan span = startSpan(OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, action.getClass(), actionRequest.getClass());

      actionListener = new TransportActionListener<>(actionRequest, actionListener, span);

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
      // span finished by TransportActionListener
    }
  }
}
