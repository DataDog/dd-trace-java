package datadog.trace.instrumentation.springcore

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.springframework.util.StreamUtils

import java.nio.charset.StandardCharsets

class StreamUtilsInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test'(){
    setup:
    InstrumentationBridge.clearIastModules()
    final module= Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    StreamUtils.copyToString(new ByteArrayInputStream("test".getBytes()), StandardCharsets.ISO_8859_1)

    then:
    1 * module.taintStringIfTainted(_ as String, _ as InputStream)
    0 * _
  }
}
