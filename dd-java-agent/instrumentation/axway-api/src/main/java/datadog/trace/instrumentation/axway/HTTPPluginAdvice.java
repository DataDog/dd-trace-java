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

  public static final String AXWAY_REQUEST = "axway.request";

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final Object stateInstance,
      @Advice.Argument(value = 2) final Object serverTransaction) {
    final AgentSpan span = startSpan(AXWAY_REQUEST);
    final AgentScope scope = activateSpan(span);
    span.setMeasured(true);
    setTag(span, Tags.HTTP_METHOD, serverTransaction, "getMethod");
    setTag(span, Tags.HTTP_URL, serverTransaction, "getURI");

    // DECORATE.afterStart(span);
    // DECORATE.onRequest(span, stateInstance);

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(value = 2) final Object serverTransaction,
      @Advice.This final Object httpPlugin,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
    }

    //    try {
    //      // ServerTransaction extends HTTPTransaction :
    //      Method m = serverTransaction.getClass().getDeclaredMethod("getHeaders");
    //      m.setAccessible(true);
    //      Object headers = m.invoke(serverTransaction);
    //      org.slf4j.LoggerFactory.getLogger(httpPlugin.getClass()).debug("headers: {}", headers);
    //    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
    //      org.slf4j.LoggerFactory.getLogger(serverTransaction.getClass()).debug("", e);
    //    } finally {
    //      scope.close();
    //      span.finish();
    //    }
  }

  public static void setTag(AgentSpan span, String tag, Object obj, String methodName) {
    span.setTag(tag, invokeMethod(obj, methodName).toString());
  }

  public static Object invokeMethod(Object obj, String methodName) {
    try {
      Method m = obj.getClass().getDeclaredMethod(methodName);
      m.setAccessible(true);
      Object v = m.invoke(obj);
      org.slf4j.LoggerFactory.getLogger(obj.getClass()).debug("{}(): {}", methodName, v);
      return v;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      System.out.println("HTTPPluginAdvice: exception on reflection: " + e);
      e.printStackTrace();
    }
    return "";
  }
}
