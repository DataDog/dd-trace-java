package com.datadog.iast.model

import datadog.trace.test.util.DDSpecification

class LocationTest extends DDSpecification {

  void 'forStack'() {
    given:
    final stack = new StackTraceElement("declaringClass", "methodName", "fileName", 42)

    when:
    final location = Location.forStack(stack)

    then:
    location.getPath() == "declaringClass"
    location.getLine() == 42
  }
}
