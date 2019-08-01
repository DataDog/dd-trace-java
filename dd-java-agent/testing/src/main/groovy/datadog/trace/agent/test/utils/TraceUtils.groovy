package datadog.trace.agent.test.utils

import datadog.opentracing.DDSpan
import datadog.trace.agent.decorator.BaseDecorator
import datadog.trace.agent.test.asserts.TraceAssert
import lombok.SneakyThrows

import java.util.concurrent.Callable

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.instrumentation.api.AgentTracer.startSpan

class TraceUtils {

  private static BaseDecorator decorator(String spanName) {
    return new BaseDecorator() {
      protected String[] instrumentationNames() {
        return new String[0]
      }

      @Override
      String spanName() {
        return spanName
      }

      protected String spanType() {
        return null
      }

      protected String component() {
        return null
      }
    }
  }

  @SneakyThrows
  static <T> T runUnderTrace(final String rootOperationName, final Callable<T> r) {
    def decorator = decorator(rootOperationName)
    def span = startSpan(decorator)
    def scope = activateSpan(span)
    try {
      scope.setAsyncPropagation(true)
      return r.call()
    } catch (final Exception e) {
      decorator.onError(scope, e)
      throw e
    } finally {
      span.finish()
      scope.close()
    }
  }

  static basicSpan(TraceAssert trace, int index, String spanName, Object parentSpan = null, Throwable exception = null) {
    basicSpan(trace, index, spanName, spanName, parentSpan, exception)
  }

  static basicSpan(TraceAssert trace, int index, String operation, String resource, Object parentSpan = null, Throwable exception = null) {
    trace.span(index) {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      serviceName "unnamed-java-app"
      operationName operation
      resourceName resource
      errored exception != null
      tags {
        defaultTags()
        if (exception) {
          errorTags(exception.class, exception.message)
        }
      }
    }
  }
}
