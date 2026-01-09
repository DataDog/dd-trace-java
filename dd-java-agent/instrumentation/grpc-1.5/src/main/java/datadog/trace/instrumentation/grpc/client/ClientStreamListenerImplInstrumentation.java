package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener;
import net.bytebuddy.asm.Advice;

public class ClientStreamListenerImplInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.grpc.internal.ClientCallImpl$ClientStreamListenerImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(
        named("exceptionThrown")
            .and(takesArgument(0, named("io.grpc.Status")))
            .and(takesArguments(1)),
        getClass().getName() + "$ExceptionThrown");
    transformer.applyAdvice(
        namedOneOf("messageRead", "messagesAvailable"), getClass().getName() + "$RecordActivity");
    transformer.applyAdvice(named("headersRead"), getClass().getName() + "$RecordHeaders");
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void capture(@Advice.This ClientStreamListener listener) {
      // instrumentation of ClientCallImpl::start ensures this scope is present and valid
      AgentSpan span = activeSpan();
      if (null != span) {
        InstrumentationContext.get(ClientStreamListener.class, AgentSpan.class).put(listener, span);
      }
    }
  }

  public static final class ExceptionThrown {
    @Advice.OnMethodEnter
    public static void exceptionThrown(
        @Advice.This ClientStreamListener listener, @Advice.Argument(0) Status status) {
      if (null != status) {
        AgentSpan span =
            InstrumentationContext.get(ClientStreamListener.class, AgentSpan.class).get(listener);
        if (null != span) {
          DECORATE.onError(span, status.getCause());
          DECORATE.beforeFinish(span);
          span.finish();
        }
      }
    }
  }

  public static final class RecordActivity {

    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ClientStreamListener listener) {
      // activate the span so serialisation work is accounted for, whichever thread the work is done
      // on
      AgentSpan span =
          InstrumentationContext.get(ClientStreamListener.class, AgentSpan.class).get(listener);
      if (span != null) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  /*
  A call to 'headersAvailable' is optional - meaning that it may not appear at all but if it appears
  it will be followed by a call to `messageRead`. In order to properly cooperate with the `messageRead` instrumentation
  we must make sure that when this method is finished the associated span is 'migrated' - such that `messageRead`
  instrumentation can correctly 'resume' the span.
   */
  public static final class RecordHeaders {

    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ClientStreamListener listener) {
      // activate the span so serialisation work is accounted for, whichever thread the work is done
      // on
      AgentSpan span =
          InstrumentationContext.get(ClientStreamListener.class, AgentSpan.class).get(listener);
      if (span != null) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }
}
