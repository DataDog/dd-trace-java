package core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
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
class MultiMapInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }


  void 'test that get() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([key: 'value'], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    instance.get('key')

    then:
    1 * module.findSource(instance) >> { null }
    0 * _

    when:
    instance.get('key')

    then:
    1 * module.findSource(instance) >> { mockedSource(origin) }
    1 * module.taint('value', origin, 'key')

    where:
    instance << multiMaps()
  }

  void 'test that getAll() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([[key: 'value1'], [key: 'value2']], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    instance.getAll('key')

    then:
    1 * module.findSource(instance) >> { null }
    0 * _

    when:
    instance.getAll('key')

    then:
    1 * module.findSource(instance) >> { mockedSource(origin) }
    1 * module.taint('value1', origin, 'key')
    1 * module.taint('value2', origin, 'key')

    where:
    instance << multiMaps()
  }

  void 'test that names() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([[key: 'value1'], [key: 'value2']], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    instance.names()

    then:
    1 * module.findSource(instance) >> { null }
    0 * _

    when:
    instance.names()

    then:
    1 * module.findSource(instance) >> { mockedSource(origin) }
    1 * module.taint('key', namedSource(origin), 'key')

    where:
    instance << multiMaps()
  }

  // some implementations do not override the entries() method so we will lose propagation in those cases
  @IgnoreIf({ !MultiMapInstrumentationTest.hasMethod(data['instance'].class, 'entries')})
  void 'test that entries() is instrumented'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    addAll([[key: 'value1'], [key: 'value2']], instance)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    instance.entries()

    then:
    1 * module.findSource(instance) >> { null }
    0 * _

    when:
    instance.entries()

    then:
    1 * module.findSource(instance) >> { mockedSource(origin) }
    1 * module.taint('key', namedSource(origin), 'key')
    1 * module.taint('value1', origin, 'key')
    1 * module.taint('value2', origin, 'key')

    where:
    instance << multiMaps()
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
