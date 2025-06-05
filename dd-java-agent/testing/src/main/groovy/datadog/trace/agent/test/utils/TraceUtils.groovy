package datadog.trace.agent.test.utils

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator
import datadog.trace.core.DDSpan

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

    protected CharSequence component() {
      return null
    }
  }

  static <T> T runUnderTraceAsync(final String rootOperationName, final Callable<T> r) {
    return runUnderTrace(rootOperationName, true, true, r)
  }

  static <T> T runUnderTrace(final String rootOperationName, final Callable<T> r) {
    return runUnderTrace(rootOperationName, true, r)
  }

  static <T> T runUnderTraceAsync(final String rootOperationName, final boolean inheritCurrent, final Callable<T> r) {
    return runUnderTrace(rootOperationName, inheritCurrent, true, r)
  }

  static <T> T runUnderTrace(final String rootOperationName, final boolean inheritCurrent, final Callable<T> r) {
    return runUnderTrace(rootOperationName, inheritCurrent, false, r)
  }

  static <T> T runUnderTrace(final String rootOperationName, final boolean inheritCurrent, final boolean async, final Callable<T> r) {
    final AgentSpan span = inheritCurrent ? startSpan(rootOperationName) : startSpan(rootOperationName, null)
    DECORATOR.afterStart(span)

    AgentScope scope = activateSpan(span)

    try {
      return r.call()
    } catch (final Exception e) {
      handleException(span, e)
      throw e
    } finally {
      DECORATOR.beforeFinish(span)
      scope.close()
      span.finish()
    }
  }

  static <T> void runnableUnderTraceAsync(final String rootOperationName, final Runnable r) {
    runUnderTraceAsync(rootOperationName, new Callable<T>() {
        @Override
        T call() throws Exception {
          r.run()
          return null
        }
      })
  }

  static <T> void runnableUnderTrace(final String rootOperationName, final Runnable r) {
    runUnderTrace(rootOperationName, new Callable<T>() {
        @Override
        T call() throws Exception {
          r.run()
          return null
        }
      })
  }

  static handleException(final AgentSpan span, final Exception e) {
    DECORATOR.onError(span, e)
  }

  static basicSpan(TraceAssert trace, String spanName, Object parentSpan = null, Throwable exception = null, Map<String, Serializable> extraTags = [:]) {
    basicSpan(trace, spanName, spanName, parentSpan, exception, extraTags)
  }

  static basicSpan(TraceAssert trace, int index, String spanName, Object parentSpan = null, Throwable exception = null, Map<String, Serializable> extraTags = [:]) {
    basicSpan(trace, index, spanName, spanName, parentSpan, exception, extraTags)
  }

  static basicSpan(TraceAssert trace, String operation, String resource, Object parentSpan = null, Throwable exception = null, Map<String, Serializable> extraTags = [:]) {
    int index = trace.nextSpanId()
    basicSpan(trace, index, operation, resource, parentSpan, exception, extraTags)
  }

  static basicSpan(TraceAssert trace, int index, String operation, String resource, Object parentSpan = null, Throwable exception = null, Map<String, Serializable> extraTags = [:]) {
    trace.span(index) {
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
        addTags(extraTags)
      }
    }
  }
}
