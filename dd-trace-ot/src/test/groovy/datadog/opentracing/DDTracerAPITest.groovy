package datadog.opentracing

import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.common.sampling.AllSampler
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.test.util.DDSpecification
import io.opentracing.Scope
import io.opentracing.Span

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG

class DDTracerAPITest extends DDSpecification {
  def "verify sampler/writer constructor"() {
    setup:
    def writer = new ListWriter()
    def sampler = new RateByServiceSampler()

    when:
    def tracerOT = new DDTracer(DEFAULT_SERVICE_NAME, writer, sampler)
    def tracer = tracerOT.tracer

    then:
    tracer.serviceName == DEFAULT_SERVICE_NAME
    tracer.sampler == sampler
    tracer.writer == writer
    tracer.localRootSpanTags[RUNTIME_ID_TAG].size() > 0 // not null or empty
    tracer.localRootSpanTags[LANGUAGE_TAG_KEY] == LANGUAGE_TAG_VALUE

    cleanup:
    tracer.close()
  }


  def 'tracer reports user details on the top span'() {
    setup:
    List<MutableSpan> spans
    datadog.trace.common.writer.Writer writer = Mock()
    DDTracer tracer = new DDTracer(DEFAULT_SERVICE_NAME, writer, new AllSampler())

    when:
    Span root = tracer.buildSpan('operation').start()
    Scope scopeRoot = tracer.activateSpan(root)
    Span child = tracer.buildSpan('my_child').asChildOf(root).start()
    Scope scopeChild = tracer.activateSpan(child)
    tracer.addUserDetails('my-user-id')
      .withName('John Smith')
      .withEmail('foo@example.com')
      .withRole('admin')
      .withSessionId('the-session-id')
      .withCustomData('custom', 'data')
    scopeChild.close()
    child.finish()
    scopeRoot.close()
    root.finish()

    then:
    1 * writer.write(_) >> { spans = it[0] }
    spans[0].operationName == 'operation'
    spans[0].tags.findAll { it.key.startsWith('usr.') } == [
      'usr.id': 'my-user-id',
      'usr.name': 'John Smith',
      'usr.email': 'foo@example.com',
      'usr.role': 'admin',
      'usr.session_id': 'the-session-id',
      'usr.custom': 'data'
    ]

    cleanup:
    tracer.close()
  }
}
