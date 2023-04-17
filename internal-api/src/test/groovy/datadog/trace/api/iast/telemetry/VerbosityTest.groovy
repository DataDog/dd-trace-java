package datadog.trace.api.iast.telemetry

import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class VerbosityTest extends DDSpecification {

  void 'test level is enabled'() {
    when:
    final debug = verbosity.isDebugEnabled()
    final info = verbosity.isInformationEnabled()
    final mandatory = verbosity.isMandatoryEnabled()

    then:
    debug == verbosity.ordinal() >= Verbosity.DEBUG.ordinal()
    info == verbosity.ordinal() >= Verbosity.INFORMATION.ordinal()
    mandatory == verbosity.ordinal() >= Verbosity.MANDATORY.ordinal()

    where:
    verbosity << Verbosity.values().toList()
  }

  void 'test get level'() {
    given:
    injectSysConfig('dd.iast.telemetry.verbosity', verbosity.name())

    when:
    injectSysConfig('dd.telemetry.metrics.enabled', 'true')

    then:
    Verbosity.getLevel() == verbosity

    then:
    injectSysConfig('dd.telemetry.metrics.enabled', 'false')

    then:
    Verbosity.getLevel() == Verbosity.OFF

    where:
    verbosity << Verbosity.values().toList()
  }
}
