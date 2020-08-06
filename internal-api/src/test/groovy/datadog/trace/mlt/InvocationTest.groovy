package datadog.trace.mlt

import datadog.trace.util.test.DDSpecification

class InvocationTest extends DDSpecification {
  def "get immediate caller"() {
    when:
    def caller = Invocation.getCaller(0)

    then:
    println caller.className
    caller.className != null
  }

  def "get all callers"() {
    when:
    def callers = Invocation.getCallers()

    then:
    !callers.isEmpty()

    def hasMethod = 0
    def noMethod = 0
    callers.each {
      it.className != null
      if (it.methodName != null) {
        hasMethod++
      } else {
        noMethod++
      }
    }
    hasMethod == 0 || noMethod == 0
  }
}
