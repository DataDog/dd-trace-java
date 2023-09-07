package datadog.opentracing


import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG

class DDTracerAPITest extends DDSpecification {
  def "verify sampler/writer constructor"() {
    setup:
    def writer = new ListWriter()
    def sampler = new RateByServiceTraceSampler()

    when:
    def tracerOT = new DDTracer(DEFAULT_SERVICE_NAME, writer, sampler)
    def tracer = tracerOT.tracer

    then:
    tracer.serviceName == DEFAULT_SERVICE_NAME
    tracer.initialSampler == sampler
    tracer.writer == writer
    tracer.localRootSpanTags[RUNTIME_ID_TAG].size() > 0 // not null or empty
    tracer.localRootSpanTags[LANGUAGE_TAG_KEY] == LANGUAGE_TAG_VALUE

    cleanup:
    tracer.close()
  }
}
