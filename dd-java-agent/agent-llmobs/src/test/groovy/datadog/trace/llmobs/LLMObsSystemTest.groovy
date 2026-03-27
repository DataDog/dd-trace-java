package datadog.trace.llmobs

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.test.util.DDSpecification

class LLMObsSystemTest extends DDSpecification {

  void 'start disabled when llmobs is disabled'() {
    setup:
    injectSysConfig('llmobs.enabled', 'false')
    rebuildConfig()
    final inst = Mock(java.lang.instrument.Instrumentation)
    final sco = Mock(SharedCommunicationObjects)

    when:
    LLMObsSystem.start(inst, sco)

    then:
    0 * sco._
  }

  void 'start disabled when trace is disabled'() {
    setup:
    injectSysConfig('llmobs.enabled', 'true')
    injectSysConfig('trace.enabled', 'false')
    rebuildConfig()
    final inst = Mock(java.lang.instrument.Instrumentation)
    final sco = Mock(SharedCommunicationObjects)

    when:
    LLMObsSystem.start(inst, sco)

    then:
    0 * sco._
  }
}
