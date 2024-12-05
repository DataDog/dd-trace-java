package org.mule.runtime.api.util

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan

class MuleTestUtil {
  static DDSpan muleSpan(TraceAssert traceAssert, String componentType, String componentName, DDSpan parent = null, boolean error = false) {
    def ret
    traceAssert.span {
      ret = it.span
      operationName "mule.action"
      resourceName "$componentType $componentName"
      if (parent != null) {
        childOf parent
      } else {
        childOfPrevious()
      }
      errored error
      spanType DDSpanTypes.MULE
      tags {
        "$Tags.COMPONENT" "mule"
        "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_INTERNAL"
        "mule.location" { String }
        "mule.correlation_id" { String }
        if (error) {
          "$DDTags.ERROR_TYPE" { String }
          "$DDTags.ERROR_MSG" { String }
          "$DDTags.ERROR_STACK" { String }
        }
        defaultTags()
      }
    }
    ret
  }
}
