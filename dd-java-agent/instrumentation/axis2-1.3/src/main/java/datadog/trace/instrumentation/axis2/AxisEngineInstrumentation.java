package datadog.trace.instrumentation.axis2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_CONTINUATION_KEY;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_MESSAGE;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Tracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.Handler.InvocationResponse;

@AutoService(InstrumenterModule.class)
public final class AxisEngineInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AxisEngineInstrumentation() {
    super("axis2");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.axis2.engine.AxisEngine";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AxisMessageDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("receive", "send", "sendFault"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$HandleMessageAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("resumeReceive", "resumeSend", "resumeSendFault"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$ResumeMessageAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("invoke"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$InvokeMessageAdvice");
  }

  public static final class HandleMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginProcessingMessage(
        @Advice.Argument(0) final MessageContext message) {
      // only create a span if the message has a clear action and there's a surrounding request
      if (DECORATE.shouldTrace(message)) {
        AgentSpan span = startSpan(AXIS2_MESSAGE);
        DECORATE.afterStart(span);
        DECORATE.onMessage(span, message);
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishProcessingMessage(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final MessageContext message,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span, message);
      scope.close();
      span.finish();
    }
  }

  public static final class ResumeMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginResumingMessage(
        @Advice.Argument(0) final MessageContext message) {
      Object continuation = message.getSelfManagedData(Tracer.class, AXIS2_CONTINUATION_KEY);
      if (null != continuation) {
        message.removeSelfManagedData(Tracer.class, AXIS2_CONTINUATION_KEY);
        // resuming is a distinct operation, so create a new span under the original request
        try (AgentScope parentScope = ((AgentScope.Continuation) continuation).activate()) {
          AgentSpan span = startSpan(AXIS2_MESSAGE);
          DECORATE.afterStart(span);
          DECORATE.onMessage(span, message);
          return activateSpan(span);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishResumingMessage(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final MessageContext message,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span, message);
      scope.close();
      span.finish();
    }
  }

  public static final class InvokeMessageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void finishInvokingMessage(
        @Advice.Argument(0) final MessageContext message,
        @Advice.Return final InvocationResponse response) {
      if (InvocationResponse.SUSPEND == response
          && !message.containsSelfManagedDataKey(Tracer.class, AXIS2_CONTINUATION_KEY)) {
        AgentSpan span = activeSpan();
        if (null != span && DECORATE.sameTrace(span, message)) {
          // record continuation in the message so we can re-activate it on resume
          // we use the self-managed area of the message which is private/internal
          message.setSelfManagedData(Tracer.class, AXIS2_CONTINUATION_KEY, captureSpan(span));
        }
      }
    }
  }
}
