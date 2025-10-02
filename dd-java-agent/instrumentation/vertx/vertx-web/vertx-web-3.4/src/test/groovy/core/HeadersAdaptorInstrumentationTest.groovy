package core

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import groovy.transform.CompileDynamic
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.vertx.core.MultiMap
import io.vertx.core.http.impl.HeadersAdaptor
import io.vertx.core.http.impl.Http2HeadersAdaptor

import static org.junit.jupiter.api.Assumptions.assumeTrue

@CompileDynamic
class HeadersAdaptorInstrumentationTest extends InstrumentationSpecification {

  private Object iastCtx

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void setup() {
    iastCtx = Stub(IastContext)
  }

  void 'test that #name get() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([key: 'value'], headers)

    when:
    runUnderIastTrace { headers.get('key') }

    then:
    1 * module.taintStringIfTainted(iastCtx, 'value', headers, SourceTypes.REQUEST_HEADER_VALUE, 'key')

    where:
    headers        | name
    httpAdaptor()  | 'HeadersAdaptor'
    http2Adaptor() | 'Http2HeadersAdaptor'
  }

  void 'test that #name getAll() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    runUnderIastTrace { headers.getAll('key') }

    then:
    1 * module.isTainted(iastCtx, headers) >> { false }
    0 * _

    when:
    runUnderIastTrace { headers.getAll('key') }

    then:
    1 * module.isTainted(iastCtx, headers) >> { true }
    1 * module.taintString(iastCtx, 'value1', SourceTypes.REQUEST_HEADER_VALUE, 'key')
    1 * module.taintString(iastCtx, 'value2', SourceTypes.REQUEST_HEADER_VALUE, 'key')

    where:
    headers        | name
    httpAdaptor()  | 'HeadersAdaptor'
    http2Adaptor() | 'Http2HeadersAdaptor'
  }

  void 'test that #name names() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    runUnderIastTrace { headers.names() }

    then:
    1 * module.isTainted(iastCtx, headers) >> { false }
    0 * _

    when:
    runUnderIastTrace { headers.names() }

    then:
    1 * module.isTainted(iastCtx, headers) >> { true }
    1 * module.taintString(iastCtx, 'key', SourceTypes.REQUEST_HEADER_NAME, 'key')

    where:
    headers        | name
    httpAdaptor()  | 'HeadersAdaptor'
    http2Adaptor() | 'Http2HeadersAdaptor'
  }

  void 'test that #name entries() is instrumented'() {
    given:
    // latest versions of vertx 3.x define the entries in the MultiMap interface, so we will lose propagation
    assumeTrue(hasMethod(headers.getClass(), 'entries'))
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    addAll([[key: 'value1'], [key: 'value2']], headers)

    when:
    final result = runUnderIastTrace { headers.entries() }

    then:
    1 * module.isTainted(iastCtx, headers) >> { false }
    0 * _

    when:
    runUnderIastTrace { headers.entries() }

    then:
    1 * module.isTainted(iastCtx, headers) >> { true }
    result.collect { it.key }.unique().each {
      1 * module.taintString(iastCtx, it, SourceTypes.REQUEST_HEADER_NAME, it)
    }
    result.each {
      1 * module.taintString(iastCtx, it.value, SourceTypes.REQUEST_HEADER_VALUE, it.key)
    }

    where:
    headers        | name
    httpAdaptor()  | 'HeadersAdaptor'
    http2Adaptor() | 'Http2HeadersAdaptor'
  }

  protected <E> E runUnderIastTrace(Closure<E> cl) {
    final ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    final span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      return AgentTracer.activateSpan(span).withCloseable(cl)
    } finally {
      span.finish()
    }
  }

  private static boolean hasMethod(final Class<?> target, final String name, final Class<?>... types) {
    try {
      target.getDeclaredMethod(name, types) != null
    } catch (Throwable ignored) {
      return false
    }
  }

  private static MultiMap httpAdaptor() {
    return new HeadersAdaptor(new DefaultHttpHeaders())
  }

  private static MultiMap http2Adaptor() {
    return new Http2HeadersAdaptor(new DefaultHttp2Headers())
  }

  private static void addAll(final Map<String, String> map, final MultiMap headers) {
    map.each { key, value -> headers.add(key, value) }
  }

  private static void addAll(final List<Map<String, String>> list, final MultiMap headers) {
    list.each { addAll(it, headers) }
  }
}
