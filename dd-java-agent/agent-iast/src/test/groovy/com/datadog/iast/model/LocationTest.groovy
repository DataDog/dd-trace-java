package com.datadog.iast.model

import datadog.trace.api.DDId
import datadog.trace.test.util.DDSpecification

class LocationTest extends DDSpecification {

  void 'forStack'() {
    given:
    final spanId = DDId.from(123456)
    final stack = new StackTraceElement("declaringClass", "methodName", "fileName", 42)

    when:
    final location = Location.forSpanAndStack(spanId, stack)

    then:
    location.getSpanId() == spanId
    location.getPath() == "declaringClass"
    location.getLine() == 42
  }
}
