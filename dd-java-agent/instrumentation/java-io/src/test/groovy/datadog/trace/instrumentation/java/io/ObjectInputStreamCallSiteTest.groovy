package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UntrustedDeserializationModule
import foo.bar.TestObjectInputStreamSuite

import foo.bar.TestCustomObjectInputStream

class ObjectInputStreamCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test onObject'() {
    setup:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestObjectInputStreamSuite.init(inputStream())

    then:
    1 * module.onObject(_)
  }

  void 'test super call to ObjectInputStream.<init>'() {
    given:
    final iastModule = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestCustomObjectInputStream(inputStream())

    then:
    // TODO APPSEC-57009 calls to super are only instrumented by after callsites
    0 * iastModule.onObject(_)
  }

  private static InputStream inputStream() {
    final baos = new ByteArrayOutputStream()
    new ObjectOutputStream(baos).writeObject([:])
    return new ByteArrayInputStream(baos.toByteArray())
  }
}
