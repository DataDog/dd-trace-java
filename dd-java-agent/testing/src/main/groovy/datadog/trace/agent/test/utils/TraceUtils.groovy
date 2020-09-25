package datadog.trace.agent.test.utils

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator
import datadog.trace.core.DDSpan
import lombok.SneakyThrows

import java.util.concurrent.Callable

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

class TraceUtils {

  private static final BaseDecorator DECORATOR = new BaseDecorator() {
    protected String[] instrumentationNames() {
      return new String[0]
    }

    protected CharSequence spanType() {
      return null
    }

    protected String component() {
      return null
    }
  }

  @SneakyThrows
  static <T> T runUnderTrace(final String rootOperationName, final Callable<T> r) {
    final AgentSpan span = startSpan(rootOperationName)
    DECORATOR.afterStart(span)

    AgentScope scope = activateSpan(span)
    scope.setAsyncPropagation(true)

    try {
      return r.call()
    } catch (final Exception e) {
      DECORATOR.onError(span, e)
      throw e
    } finally {
      DECORATOR.beforeFinish(span)
      scope.close()
      span.finish()
    }
  }

  static basicSpan(TraceAssert trace, String spanName, Object parentSpan = null, Throwable exception = null) {
    basicSpan(trace, spanName, spanName, parentSpan, exception)
  }

  static basicSpan(TraceAssert trace, String operation, String resource, Object parentSpan = null, Throwable exception = null) {
    trace.span {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      hasServiceName()
      operationName operation
      resourceName resource
      errored exception != null
      tags {
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        defaultTags()
      }
      metrics {
        defaultMetrics()
      }
    }
  }
}
