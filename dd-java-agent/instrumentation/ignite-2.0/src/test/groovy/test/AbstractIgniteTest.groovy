package test

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags

abstract class AbstractIgniteTest extends IgniteBaseTest {
  def setupSpec() {
    startIgnite()
  }

  def cleanupSpec() {
    stopIgnite()
  }

  void assertIgniteCall(TraceAssert trace, String name, String cacheName, boolean parentSpan = true) {
    trace.span {
      serviceName service()
      resourceName name + " on " + cacheName
      operationName operation()
      spanType DDSpanTypes.CACHE
      errored false
      if (parentSpan) {
        parent()
      } else {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" "ignite-cache"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "ignite"
        "ignite.operation" name
        "ignite.cache.name" cacheName
        if (igniteClient.name()) {
          "ignite.instance" igniteClient.name()
        }
        "ignite.version" igniteClient.version().toString()
        defaultTagsNoPeerService()
      }
    }
  }
}
