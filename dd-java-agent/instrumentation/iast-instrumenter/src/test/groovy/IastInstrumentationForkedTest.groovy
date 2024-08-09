import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.IastConfig
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import spock.lang.Shared

class IastInstrumentationForkedTest extends AgentTestRunner {

  @Shared
  boolean iastEnabled = false

  @Shared
  boolean raspEnabled = false

  @Shared
  Set<Class<?>> expectedCallSites = []

  @Override
  protected void configurePreAgent() {
    injectSysConfig(IastConfig.IAST_ENABLED, iastEnabled.toString())
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, raspEnabled.toString())
  }

  void 'test Iast Instrumentation call site supplier'() {
    given:
    final instrumentation = new IastInstrumentation()

    when:
    final callSites = instrumentation.callSites().get().toList()

    then:
    callSites.size() == expectedCallSites.size()
    if (expectedCallSites) {
      expectedCallSites.each { expected ->
        final site = callSites.find { expected.isInstance(it) }
        assert site != null
        if (expected == MockCallSitesWithTelemetry) {
          assert site.verbosity == Config.get().iastTelemetryVerbosity
        }
      }
    }
  }
}

class IastForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = true

  boolean raspEnabled = false

  Set<Class<?>> expectedCallSites = [MockCallSites, MockCallSitesWithTelemetry]
}

class RaspForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = false

  boolean raspEnabled = true

  Set<Class<?>> expectedCallSites = [MockRaspCallSites]
}

class AllCallSitesForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = true

  boolean raspEnabled = true

  Set<Class<?>> expectedCallSites = [MockCallSites, MockCallSitesWithTelemetry, MockRaspCallSites]
}
