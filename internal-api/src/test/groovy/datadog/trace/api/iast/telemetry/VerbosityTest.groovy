package datadog.trace.api.iast.telemetry

import groovy.transform.CompileDynamic
import spock.lang.Specification

@CompileDynamic
class VerbosityTest extends Specification {

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
}
