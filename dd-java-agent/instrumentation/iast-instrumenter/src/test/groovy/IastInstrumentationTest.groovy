import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.IastConfig
import datadog.trace.instrumentation.iastinstrumenter.IastHardcodedSecretListener
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import net.bytebuddy.description.type.TypeDescription

class IastInstrumentationTest extends InstrumentationSpecification {

  void 'test Iast Instrumentation type matching'() {
    given:
    final instrumentation = new IastInstrumentation()
    final typeDescription = Stub(TypeDescription) {
      getName() >> type
    }

    when:
    final matches = instrumentation.callerType().matches(typeDescription)

    then:
    matches == expected

    where:
    type                     | expected
    'org.jsantos.Tool'       | true
    'oracle.jdbc.Connection' | false
  }

  void 'test Iast Instrumentation hardcoded secret listener'() {
    given:
    injectSysConfig(IastConfig.IAST_ENABLED, "true")
    injectSysConfig(IastConfig.IAST_HARDCODED_SECRET_ENABLED, enabled)
    final instrumentation = new IastInstrumentation()
    final callSites = instrumentation.callSites().get().toList()

    when:
    final advices = instrumentation.buildAdvices(callSites)

    then:
    final withHardcodedSecretListener = advices.listeners.find({ it instanceof IastHardcodedSecretListener }) != null
    withHardcodedSecretListener == expected

    where:
    enabled | expected
    'true'  | true
    'false' | false
  }
}
