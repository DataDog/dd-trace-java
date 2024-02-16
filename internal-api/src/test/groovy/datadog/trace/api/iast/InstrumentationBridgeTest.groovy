package datadog.trace.api.iast

import datadog.trace.test.util.DDSpecification

class InstrumentationBridgeTest extends DDSpecification {

  def cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void '#module can be registered'() {
    setup:
    final instance = Stub(module)
    InstrumentationBridge.registerIastModule(instance)

    when:
    def result = InstrumentationBridge.getIastModule(module)

    then:
    instance == result

    where:
    module << InstrumentationBridge.iastModules
  }

  void 'unsupported modules throw exceptions'() {
    when:
    InstrumentationBridge.registerIastModule(Stub(IastModule))

    then:
    thrown(UnsupportedOperationException)

    when:
    InstrumentationBridge.getIastModule(IastModule)

    then:
    thrown(UnsupportedOperationException)
  }
}
