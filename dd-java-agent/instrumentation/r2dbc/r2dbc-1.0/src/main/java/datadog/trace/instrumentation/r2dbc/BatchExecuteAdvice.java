package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_BATCH;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Result;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.reactivestreams.Publisher;

public class BatchExecuteAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This Batch batch) {
    int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Batch.class);
    if (callDepth > 0) {
      return null;
    }

    R2dbcConnectionInfo connInfo =
        InstrumentationContext.get(Batch.class, R2dbcConnectionInfo.class).get(batch);

    AgentSpan span = startSpan("r2dbc-spi", DATABASE_QUERY);
    DECORATE.afterStart(span);
    DECORATE.onDatabase(span, connInfo);
    span.setResourceName(R2DBC_BATCH);

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter AgentScope scope,
      @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
          Publisher<? extends Result> publisher,
      @Advice.Thrown Throwable throwable) {
    CallDepthThreadLocalMap.decrementCallDepth(Batch.class);
    if (scope == null) {
      return;
    }

    AgentSpan span = scope.span();
    scope.close();

    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
    } else if (publisher != null) {
      publisher = new TracingPublisher(publisher, span);
    } else {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
