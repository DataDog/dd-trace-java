package core

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic
import io.vertx.core.buffer.Buffer
import io.vertx.core.buffer.impl.BufferImpl

@CompileDynamic
class BufferInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test that Buffer.#methodName is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final buffer = taintedInstance(SourceTypes.REQUEST_BODY)

    when:
    method.call(buffer)

    then:
    1 * module.taintStringIfTainted(_, buffer)

    where:
    methodName         | method
    'toString()'       | { Buffer b -> b.toString() }
    'toString(String)' | { Buffer b -> b.toString('UTF-8') }
  }

  void 'test that Buffer.#methodName is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final buffer = new BufferImpl()
    final tainted = taintedInstance(SourceTypes.REQUEST_BODY)

    when:
    method.call(buffer, tainted)

    then:
    1 * module.taintObjectIfTainted(buffer, tainted)

    where:
    methodName                       | method
    'appendBuffer(Buffer)'           | { Buffer b, Buffer taint -> b.appendBuffer(taint) }
    'appendBuffer(buffer, int, int)' | { Buffer b, Buffer taint -> b.appendBuffer(taint, 0, taint.length()) }
  }

  private Buffer taintedInstance(final byte origin) {
    final buffer = new BufferImpl('Hello World!')
    if (buffer instanceof Taintable) {
      final source = Mock(Taintable.Source) {
        getOrigin() >> origin
      }
      (buffer as Taintable).$$DD$setSource(source)
    }
    return buffer
  }
}
