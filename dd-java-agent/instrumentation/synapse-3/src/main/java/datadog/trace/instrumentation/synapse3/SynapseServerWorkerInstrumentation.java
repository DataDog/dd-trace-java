package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.SYNAPSE_CONTINUATION_KEY;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.SYNAPSE_SPAN_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpResponse;
import org.apache.synapse.transport.passthru.SourceRequest;

@AutoService(Instrumenter.class)
public final class SynapseServerWorkerInstrumentation extends Instrumenter.Tracing {

  public SynapseServerWorkerInstrumentation() {
    super("synapse3-server", "synapse3");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.apache.synapse.transport.passthru.ServerWorker");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SynapseServerDecorator",
    };
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.apache.synapse.transport.passthru.SourceRequest"))),
        getClass().getName() + "$NewServerWorkerAdvice");
    transformation.applyAdvice(
        isMethod().and(named("run")).and(takesNoArguments()),
        getClass().getName() + "$ServerWorkerResponseAdvice");
  }

  public static final class NewServerWorkerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void createWorker(@Advice.Argument(0) final SourceRequest request) {
      TraceScope scope = activeScope();
      if (null != scope) {
        request
            .getConnection()
            .getContext()
            .setAttribute(SYNAPSE_CONTINUATION_KEY, scope.capture());
      }
    }
  }

  public static final class ServerWorkerResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginResponse(
        @Advice.FieldValue("request") final SourceRequest request) {
      TraceScope.Continuation continuation =
          (TraceScope.Continuation)
              request.getConnection().getContext().removeAttribute(SYNAPSE_CONTINUATION_KEY);
      if (null != continuation) {
        return (AgentScope) continuation.activate();
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void responseReady(
        @Advice.Enter final AgentScope scope,
        @Advice.FieldValue("request") final SourceRequest request,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      HttpResponse httpResponse = request.getConnection().getHttpResponse();
      if (null != httpResponse) {
        DECORATE.onResponse(span, httpResponse);
      }
      if (null != error) {
        DECORATE.onError(span, error);
      }
      // server worker is created in request event so be prepared to finish the span here
      // (if there's an ACK response or error we might not get a separate response event)
      if ((null != httpResponse || null != error)
          && null != request.getConnection().getContext().removeAttribute(SYNAPSE_SPAN_KEY)) {
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      } else {
        // otherwise will be finished by a separate server response event
        scope.close();
      }
    }
  }
}
