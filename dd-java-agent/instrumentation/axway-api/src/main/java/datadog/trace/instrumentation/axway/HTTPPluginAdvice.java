package datadog.trace.instrumentation.axway;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class HTTPPluginAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final Object stateInstance,
      @Advice.Argument(value = 2) final Object serverTransaction) {
    final AgentSpan span = startSpan("axway.request");
    final AgentScope scope = activateSpan(span);
    span.setMeasured(true);
    // Manually DECORATE.onRequest(span, serverTransaction); :
    setTag(span, Tags.HTTP_METHOD, serverTransaction, "getMethod");
    setTag(span, Tags.HTTP_URL, serverTransaction, "getURI");
    DECORATE.afterStart(span);

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(value = 2) final Object serverTransaction,
      @Advice.This final Object httpPlugin,
      // @Advice.Local("responseCode") Integer responseCode,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      // manual DECORATE.onResponse(span, serverTransaction):
      // span.setTag(Tags.HTTP_STATUS, responseCode); //TODO
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
    }
  }

  public static void setTag(AgentSpan span, String tag, Object obj, String methodName) {
    span.setTag(tag, invokeNoArgMethod(obj, methodName).toString());
  }

  public static Object invokeNoArgMethod(Object obj, String methodName) {
    try {
      Method m = obj.getClass().getDeclaredMethod(methodName);
      m.setAccessible(true);
      Object v = m.invoke(obj);
      org.slf4j.LoggerFactory.getLogger(obj.getClass()).debug("{}(): {}", methodName, v);
      return v;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      org.slf4j.LoggerFactory.getLogger(obj.getClass())
          .debug("Can't find method '" + methodName + "' in object " + obj, e);
    }
    return "";
  }
}
