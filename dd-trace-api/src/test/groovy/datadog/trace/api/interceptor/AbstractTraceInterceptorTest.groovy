package datadog.trace.api.interceptor

import spock.lang.Specification

class AbstractTraceInterceptorTest extends Specification {

  def "priority index is taken from enum"() {
    given:
    def priority = AbstractTraceInterceptor.Priority.values()[0]
    def interceptor = new AbstractTraceInterceptor(priority) {
        @Override
        Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
          return null
        }
      }

    expect:
    interceptor.priority() == priority.idx
  }
}
