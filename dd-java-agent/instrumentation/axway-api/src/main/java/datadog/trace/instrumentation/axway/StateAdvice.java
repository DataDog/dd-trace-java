package datadog.trace.instrumentation.axway;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;

public class StateAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This final Object stateInstance) {
    final AgentSpan span = startSpan("axway.trytransaction");
    final AgentScope scope = activateSpan(span);
    span.setMeasured(true);
    setTag(span, Tags.HTTP_METHOD, stateInstance, "verb");
    setTag(span, Tags.HTTP_URL, stateInstance, "uri");
    setTag(span, Tags.PEER_HOSTNAME, stateInstance, "host");
    setTag(span, Tags.PEER_PORT, stateInstance, "port");

    // to propagate host and port to transaction:
    //    try {
    //      Object headers = getField(stateInstance, "headers");
    //
    //      String hostVal = (String) getField(stateInstance, "host");
    //      String portVal = (String) getField(stateInstance, "port");
    //
    //      Object uriVal = getField(stateInstance, "uri").toString();
    //      String verb = (String) getField(stateInstance, "verb");
    //
    //      // to propogate inside
    //      Method m = headers.getClass().getDeclaredMethod("setHeader", String.class,
    // Object.class);
    //      m.setAccessible(true);
    //      m.invoke(headers, AxwayHTTPPluginDecorator.CORRELATION_HOST, hostVal);
    //      m.invoke(headers, AxwayHTTPPluginDecorator.CORRELATION_PORT, portVal);
    //      log.debug("StateAdvice headers after:{}}", headers);
    //    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
    //      log.debug("", e);
    //    }

    // DECORATE.afterStart(span);
    // DECORATE.onRequest(span, stateInstance);

    return scope;
  }

  public static void setTag(AgentSpan span, String tag, Object obj, String field) {
    span.setTag(tag, getField(obj, field).toString());
  }

  public static Object getField(Object obj, String fieldName) {
    try {
      Field field = obj.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      Object v = field.get(obj);
      org.slf4j.LoggerFactory.getLogger(obj.getClass()).debug("field '{}': {}", fieldName, v);
      return v;
    } catch (NoSuchFieldException | IllegalAccessException e) {
      org.slf4j.LoggerFactory.getLogger(obj.getClass())
          .debug("Can't find field '" + fieldName + "': ", e);
    }
    return "null";
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
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
  }
}
