import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.tooling.InstrumenterModule
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.IastConfig
import datadog.trace.instrumentation.iastinstrumenter.IastHardcodedSecretListener
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import net.bytebuddy.description.type.TypeDescription

class IastInstrumentationTest extends AgentTestRunner {

  void 'test Iast Instrumentation enablement'() {
    given:
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, Boolean.toString(rasp))
    final instrumentation = new IastInstrumentation()

    when:
    final enabled = instrumentation.isEnabled()
    final applicable = instrumentation.isApplicable(enabledSystems as Set<InstrumenterModule.TargetSystem>)

    then:
    enabled
    applicable == expected

    where:
    enabledSystems                                                                 | rasp  | expected
    []                                                                             | false | false
    [InstrumenterModule.TargetSystem.APPSEC]                                       | false | false
    [InstrumenterModule.TargetSystem.IAST]                                         | false | true
    [InstrumenterModule.TargetSystem.APPSEC]                                       | true  | true
    [InstrumenterModule.TargetSystem.IAST, InstrumenterModule.TargetSystem.APPSEC] | false | true
    [InstrumenterModule.TargetSystem.IAST, InstrumenterModule.TargetSystem.APPSEC] | true  | true
  }

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
