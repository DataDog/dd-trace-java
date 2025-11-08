package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.SYNAPSE_CONTINUATION_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.http.HttpResponse;
import org.apache.synapse.transport.passthru.TargetResponse;

@AutoService(InstrumenterModule.class)
public final class SynapseClientWorkerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SynapseClientWorkerInstrumentation() {
    super("synapse3-client", "synapse3");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.synapse.transport.passthru.ClientWorker";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SynapseClientDecorator",
    };
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(2, named("org.apache.synapse.transport.passthru.TargetResponse"))),
        getClass().getName() + "$NewClientWorkerAdvice");
    transformer.applyAdvice(
        isMethod().and(named("run")).and(takesNoArguments()),
        getClass().getName() + "$ClientWorkerResponseAdvice");
  }

  public static final class NewClientWorkerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void createWorker(@Advice.Argument(2) final TargetResponse response) {
      AgentScope.Continuation continuation = captureActiveSpan();
      if (continuation != noopContinuation()) {
        response.getConnection().getContext().setAttribute(SYNAPSE_CONTINUATION_KEY, continuation);
      }
    }
  }

  public static final class ClientWorkerResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beginResponse(
        @Advice.FieldValue("response") final TargetResponse response) {
      AgentScope.Continuation continuation =
          (AgentScope.Continuation)
              response.getConnection().getContext().removeAttribute(SYNAPSE_CONTINUATION_KEY);
      if (null != continuation) {
        AgentScope agentScope = continuation.activate();
        try {
          return getCurrentContext().with(agentScope.span()).attach();
        } finally {
          agentScope.close();
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void responseReceived(
        @Advice.Enter final ContextScope scope,
        @Advice.FieldValue("response") final TargetResponse response,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = spanFromContext(scope.context());
      HttpResponse httpResponse = response.getConnection().getHttpResponse();
      if (null != httpResponse && null != span) {
        DECORATE.onResponse(span, httpResponse);
      }
      if (null != error && null != span) {
        DECORATE.onError(span, error);
      }

      if (null != span) {
        if (null != httpResponse) {
          DECORATE.onResponse(span, httpResponse);
        }
        if (null != error) {
          DECORATE.onError(span, error);
        }
      }
      DECORATE.beforeFinish(scope.context());
      // no need to finish span because response event (which created the worker) does that
      scope.close();
    }
  }
}
