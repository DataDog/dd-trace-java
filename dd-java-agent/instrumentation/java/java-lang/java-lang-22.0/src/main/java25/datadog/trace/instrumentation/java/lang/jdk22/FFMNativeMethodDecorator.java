package datadog.trace.instrumentation.java.lang.jdk22;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Deque;

public final class FFMNativeMethodDecorator extends BaseDecorator {

  private static final Logger LOGGER = LoggerFactory.getLogger(FFMNativeMethodDecorator.class);
  private static final CharSequence TRACE_FFM = UTF8BytesString.create("trace-ffm");
  private static final CharSequence OPERATION_NAME = UTF8BytesString.create("trace.native");

  private static final MethodHandle START_SPAN_MH = safeFindStatic("startSpan", MethodType.methodType(void.class, String.class));
  private static final MethodHandle END_SPAN_MH = safeFindStatic("endSpan", MethodType.methodType(void.class, String.class)))

  public static final FFMNativeMethodDecorator DECORATE = new FFMNativeMethodDecorator();

  private static MethodHandle safeFindStatic(String name, MethodType methodType) {
    try {
      return MethodHandles.lookup().findStatic(FFMNativeMethodDecorator.class, name, methodType);
    } catch (Throwable t) {
      LOGGER.debug("Cannot find method {} in NativeMethodHandleWrapper", name, t);
      return null;
    }
  }

  public static MethodHandle wrap(MethodHandle original, String operationName) {
    if (START_SPAN_MH == null || END_SPAN_MH == null) {
      return original;
    }
      MethodType originalType = original.type();
      boolean isVoid = originalType.returnType() == void.class;

      MethodHandle startSpanMH = START_SPAN_MH.bindTo(operationName);
      /*
      Return a methodHandle chain that
      1. first calls startspans
      2. than calls the original
      3. As a tryfinally calls the endSpan providing the return value of (1) as argument
      4. Eventually drops the return value if the original return was void in order to have a method handle wrapped that's transparent
       */
     return null;

  }

  public static ContextScope startSpan(CharSequence resourceName) {
    AgentSpan span = AgentTracer.startSpan(TRACE_FFM.toString(), OPERATION_NAME);
    DECORATE.afterStart(span);
    span.setResourceName(resourceName);
    return AgentTracer.activateSpan(span);
  }

  public static Object endSpan(Throwable t, ContextScope scope, Object result) {
    try {
    if (scope != null) {
      final AgentSpan span = AgentSpan.fromContext(scope.context());
      scope.close();

      if (span != null) {
      if (t != null) {
        DECORATE.onError(span, t);
        span.addThrowable(t);
      }

        span.finish();
    }
      }
      } catch (Throwable ignored) {

      }
    return result;
  }


  @Override
  protected String[] instrumentationNames() {
    return new String[] {TRACE_FFM.toString()};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return TRACE_FFM;
  }


}
