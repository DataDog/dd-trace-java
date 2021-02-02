package datadog.trace.core.test

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.core.CoreTracer
import datadog.trace.core.CoreTracer.CoreTracerBuilder
import datadog.trace.test.util.DDSpecification

abstract class DDCoreSpecification extends DDSpecification {

  protected boolean useNoopStatsDClient() {
    return true
  }

  protected boolean useStrictTraceWrites() {
    return true
  }

  protected CoreTracerBuilder tracerBuilder() {
    def builder = CoreTracer.builder()
    if (useNoopStatsDClient()) {
      builder =  builder.statsDClient(new NoOpStatsDClient())
    }
    return builder.strictTraceWrites(useStrictTraceWrites())
  }
}
