import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.tooling.Instrumenter
import datadog.trace.api.Config
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import net.bytebuddy.description.type.TypeDescription

class IastInstrumentationTest extends AgentTestRunner {

  void 'test Iast Instrumentation enablement'() {
    given:
    final instrumentation = new IastInstrumentation()

    when:
    final enabled = instrumentation.isEnabled()
    final applicable = instrumentation.isApplicable(enabledSystems as Set<Instrumenter.TargetSystem>)

    then:
    enabled
    applicable == expected

    where:
    enabledSystems                                                     | expected
    []                                                                 | false
    [Instrumenter.TargetSystem.APPSEC]                                 | false
    [Instrumenter.TargetSystem.IAST]                                   | true
    [Instrumenter.TargetSystem.IAST, Instrumenter.TargetSystem.APPSEC] | true
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

  void 'test Iast Instrumentation call site supplier'() {
    given:
    final instrumentation = new IastInstrumentation()

    when:
    final callSites = instrumentation.callSites().get().toList()

    then:
    callSites.size() == 2
    callSites.find { it instanceof MockCallSites } != null
    final withTelemetry = callSites.find { it instanceof MockCallSitesWithTelemetry } as MockCallSitesWithTelemetry
    withTelemetry != null
    withTelemetry.verbosity == Config.get().iastTelemetryVerbosity
  }
}
