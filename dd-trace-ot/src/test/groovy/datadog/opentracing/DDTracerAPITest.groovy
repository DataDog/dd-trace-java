package datadog.opentracing


import datadog.trace.api.Config
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.util.test.DDSpecification

import static datadog.trace.api.Config.DEFAULT_SERVICE_NAME

class DDTracerAPITest extends DDSpecification {
  def "verify sampler/writer constructor"() {
    setup:
    def writer = new ListWriter()
    def sampler = new RateByServiceSampler()

    when:
    def tracerOT = new DDTracer(DEFAULT_SERVICE_NAME, writer, sampler)
    def tracer = tracerOT.coreTracer

    then:
    tracer.serviceName == DEFAULT_SERVICE_NAME
    tracer.sampler == sampler
    tracer.writer == writer
    tracer.localRootSpanTags[Config.RUNTIME_ID_TAG].size() > 0 // not null or empty
    tracer.localRootSpanTags[Config.LANGUAGE_TAG_KEY] == Config.LANGUAGE_TAG_VALUE
  }
}
