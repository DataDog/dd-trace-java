package core

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import groovy.transform.CompileDynamic
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.vertx.core.MultiMap
import io.vertx.core.http.impl.headers.HeadersAdaptor
import io.vertx.core.http.impl.headers.HeadersMultiMap
import io.vertx.core.http.impl.headers.Http2HeadersAdaptor
import spock.lang.IgnoreIf

import static datadog.trace.api.iast.SourceTypes.namedSource

@CompileDynamic
class MultiMapInstrumentationTest extends InstrumentationSpecification {

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
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([key: 'value'], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { instance.get('key') }

    then:
    1 * module.findSource(iastCtx, instance) >> { null }
    0 * _

    when:
    runUnderIastTrace { instance.get('key') }

    then:
    1 * module.findSource(iastCtx, instance) >> { mockedSource(origin) }
    1 * module.taintString(iastCtx, 'value', origin, 'key')

    where:
    instance << multiMaps()
    name = instance.getClass().simpleName
  }

  void 'test that #name getAll() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([[key: 'value1'], [key: 'value2']], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { instance.getAll('key') }

    then:
    1 * module.findSource(iastCtx, instance) >> { null }
    0 * _

    when:
    runUnderIastTrace { instance.getAll('key') }

    then:
    1 * module.findSource(iastCtx, instance) >> { mockedSource(origin) }
    1 * module.taintString(iastCtx, 'value1', origin, 'key')
    1 * module.taintString(iastCtx, 'value2', origin, 'key')

    where:
    instance << multiMaps()
    name = instance.getClass().simpleName
  }

  void 'test that #name names() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([[key: 'value1'], [key: 'value2']], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { instance.names() }

    then:
    1 * module.findSource(iastCtx, instance) >> { null }
    0 * _

    when:
    runUnderIastTrace { instance.names() }

    then:
    1 * module.findSource(iastCtx, instance) >> { mockedSource(origin) }
    1 * module.taintString(iastCtx, 'key', namedSource(origin), 'key')

    where:
    instance << multiMaps()
    name = instance.getClass().simpleName
  }

  // some implementations do not override the entries() method so we will lose propagation in those cases
  @IgnoreIf({ !MultiMapInstrumentationTest.hasMethod(data['instance'].class, 'entries')})
  void 'test that #name entries() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([[key: 'value1'], [key: 'value2']], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { instance.entries() }

    then:
    1 * module.findSource(iastCtx, instance) >> { null }
    0 * _

    when:
    runUnderIastTrace { instance.entries() }

    then:
    1 * module.findSource(iastCtx, instance) >> { mockedSource(origin) }
    1 * module.taintString(iastCtx, 'key', namedSource(origin), 'key')
    1 * module.taintString(iastCtx, 'value1', origin, 'key')
    1 * module.taintString(iastCtx, 'value2', origin, 'key')

    where:
    instance << multiMaps()
    name = instance.getClass().simpleName
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

  private mockedSource(final byte origin) {
    return Mock(Taintable.Source) {
      getOrigin() >> origin
    }
  }

  private static boolean hasMethod(final Class<?> target, final String name) {
    try {
      return target.getDeclaredMethods().any { it.name == name }
    } catch (Throwable e) {
      return false
    }
  }

  private List<MultiMap> multiMaps() {
    return [
      new HeadersMultiMap(),
      new HeadersAdaptor(new DefaultHttpHeaders()),
      new Http2HeadersAdaptor(new DefaultHttp2Headers())
    ]
  }

  private static void addAll(final Map<String, String> map, final MultiMap headers) {
    map.each { key, value -> headers.add(key, value) }
  }

  private static void addAll(final List<Map<String, String>> list, final MultiMap headers) {
    list.each { addAll(it, headers) }
  }
}
