package com.datadog.iast.model

import datadog.trace.test.util.DDSpecification

class LocationTest extends DDSpecification {

  void 'forStack'() {
    given:
    final spanId = 123456
    final stack = new StackTraceElement("declaringClass", "methodName", "fileName", 42)

    when:
    final location = Location.forSpanAndStack(spanId, stack)

    then:
    location.getSpanId() == spanId
    location.getPath() == "declaringClass"
    location.getLine() == 42
    location.getMethod() == stack.methodName
  }

  void 'forSpanAndClassAndMethod'() {
    given:
    final spanId = 123456
    final declaringClass = "declaringClass"
    final methodName = "methodName"

    when:
    final location = Location.forSpanAndClassAndMethod(spanId, declaringClass, methodName)

    then:
    location.getSpanId() == spanId
    location.getPath() == "declaringClass"
    location.getLine() == -1
    location.getMethod() == methodName
  }
}
