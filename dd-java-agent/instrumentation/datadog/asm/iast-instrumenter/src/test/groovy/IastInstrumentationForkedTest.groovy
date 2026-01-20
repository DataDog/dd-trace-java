import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.tooling.InstrumenterModule
import datadog.trace.api.Config
import datadog.trace.api.InstrumenterConfig
import datadog.trace.api.ProductActivation
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.IastConfig
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import spock.lang.Shared

class IastInstrumentationForkedTest extends InstrumentationSpecification {

  @Shared
  boolean iastEnabled = false

  @Shared
  def appSecActivation = ProductActivation.ENABLED_INACTIVE

  @Shared
  boolean raspEnabled = false

  @Shared
  boolean applicable = false

  @Shared
  Set<Class<?>> expectedCallSites = []

  Set<InstrumenterModule.TargetSystem> enabledSystems

  @Override
  protected void configurePreAgent() {
    injectSysConfig(IastConfig.IAST_ENABLED, iastEnabled.toString())
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, raspEnabled.toString())
    enabledSystems = new HashSet<>()
    if (iastEnabled) {
      enabledSystems.add(InstrumenterModule.TargetSystem.IAST)
    }
    if (appSecActivation == ProductActivation.ENABLED_INACTIVE) {
      enabledSystems.add(InstrumenterModule.TargetSystem.APPSEC)
    } else if (appSecActivation == ProductActivation.FULLY_ENABLED) {
      enabledSystems.add(InstrumenterModule.TargetSystem.APPSEC)
      injectSysConfig(AppSecConfig.APPSEC_ENABLED, "true")
    } else {
      injectSysConfig(AppSecConfig.APPSEC_ENABLED, "false")
    }
    if (InstrumenterConfig.get().isRaspEnabled()) {
      enabledSystems.add(InstrumenterModule.TargetSystem.RASP)
    }
  }

  void 'test Iast Instrumentation call site supplier'() {
    given:
    final instrumentation = new IastInstrumentation()

    when:
    final shouldApply = instrumentation.isApplicable(enabledSystems)

    then:
    shouldApply == applicable

    when:
    final callSites = !applicable ? [] : instrumentation.callSites().get().toList()

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

  def appSecActivation = ProductActivation.ENABLED_INACTIVE

  boolean raspEnabled = false

  boolean applicable = true

  Set<Class<?>> expectedCallSites = [MockCallSites, MockCallSitesWithTelemetry]
}

class AppSecInactiveRaspForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = false

  def appSecActivation = ProductActivation.ENABLED_INACTIVE

  boolean raspEnabled = true

  boolean applicable = false

  Set<Class<?>> expectedCallSites = []
}

class AppSecDisabledRaspForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = false

  def appSecActivation = ProductActivation.FULLY_DISABLED

  boolean raspEnabled = true

  boolean applicable = false

  Set<Class<?>> expectedCallSites = []
}

class AppSecEnabledRaspForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = false

  def appSecActivation = ProductActivation.FULLY_ENABLED

  boolean raspEnabled = true

  boolean applicable = true

  Set<Class<?>> expectedCallSites = [MockRaspCallSites]
}

class AllCallSitesForkedTest extends IastInstrumentationForkedTest {

  boolean iastEnabled = true

  def appSecActivation = ProductActivation.FULLY_ENABLED

  boolean raspEnabled = true

  boolean applicable = true

  Set<Class<?>> expectedCallSites = [MockCallSites, MockCallSitesWithTelemetry, MockRaspCallSites]
}
