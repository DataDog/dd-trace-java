package core

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
import io.vertx.core.buffer.Buffer
import io.vertx.core.buffer.impl.BufferImpl

class BufferInstrumentationTest extends IastAgentTestRunner {

  void 'test that toString() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final buffer = taintedInstance(SourceTypes.REQUEST_BODY)

    when:
    runUnderIastTrace { method.call(buffer) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _, buffer)

    where:
    _ | method
    _ | { Buffer b -> b.toString() }
    _ | { Buffer b -> b.toString('UTF-8') }
  }

  void 'test that append() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final buffer = new BufferImpl()
    final tainted = taintedInstance(SourceTypes.REQUEST_BODY)

    when:
    runUnderIastTrace { method.call(buffer, tainted) }

    then:
    1 * module.taintIfTainted(_ as IastContext, buffer, tainted)

    where:
    _ | method
    _ | { Buffer b, Buffer taint -> b.appendBuffer(taint) }
    _ | { Buffer b, Buffer taint -> b.appendBuffer(taint, 0, taint.length()) }
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
