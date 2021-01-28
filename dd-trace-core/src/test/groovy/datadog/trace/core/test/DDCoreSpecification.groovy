package datadog.trace.core.test

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.core.CoreTracer
import datadog.trace.core.CoreTracer.CoreTracerBuilder
import datadog.trace.test.util.DDSpecification

abstract class DDCoreSpecification extends DDSpecification {

  protected boolean useNoopStatsDClient() {
    return true
  }

  protected CoreTracerBuilder tracerBuilder() {
    def builder = CoreTracer.builder()
    if (useNoopStatsDClient()) {
      return builder.statsDClient(new NoOpStatsDClient())
    }
    return builder
  }
}
