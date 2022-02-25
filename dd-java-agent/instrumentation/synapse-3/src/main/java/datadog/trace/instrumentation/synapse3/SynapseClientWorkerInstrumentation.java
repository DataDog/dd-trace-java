package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.SYNAPSE_CONTINUATION_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.http.HttpResponse;
import org.apache.synapse.transport.passthru.TargetResponse;

@AutoService(Instrumenter.class)
public final class SynapseClientWorkerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(2, named("org.apache.synapse.transport.passthru.TargetResponse"))),
        getClass().getName() + "$NewClientWorkerAdvice");
    transformation.applyAdvice(
        isMethod().and(named("run")).and(takesNoArguments()),
        getClass().getName() + "$ClientWorkerResponseAdvice");
  }

  public static final class NewClientWorkerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void createWorker(@Advice.Argument(2) final TargetResponse response) {
      AgentScope scope = activeScope();
      if (null != scope) {
        response
            .getConnection()
            .getContext()
            .setAttribute(SYNAPSE_CONTINUATION_KEY, scope.capture());
      }
    }
  }

  public static final class ClientWorkerResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginResponse(
        @Advice.FieldValue("response") final TargetResponse response) {
      AgentScope.Continuation continuation =
          (AgentScope.Continuation)
              response.getConnection().getContext().removeAttribute(SYNAPSE_CONTINUATION_KEY);
      if (null != continuation) {
        return continuation.activate();
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void responseReceived(
        @Advice.Enter final AgentScope scope,
        @Advice.FieldValue("response") final TargetResponse response,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      HttpResponse httpResponse = response.getConnection().getHttpResponse();
      if (null != httpResponse) {
        DECORATE.onResponse(scope.span(), httpResponse);
      }
      if (null != error) {
        DECORATE.onError(scope.span(), error);
      }
      // no need to finish span because response event (which created the worker) does that
      scope.close();
    }
  }
}
