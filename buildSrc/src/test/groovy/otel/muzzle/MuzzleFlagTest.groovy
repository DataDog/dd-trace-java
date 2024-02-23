package otel.muzzle

import io.opentelemetry.javaagent.tooling.muzzle.references.Flag
import spock.lang.Specification

class MuzzleFlagTest extends Specification {
  def 'check all OTel flags are converted'() {
    setup:
    def missingFlags = []

    when:
    for (final def innerClass in Flag.class.classes) {
      for (final def enumConstant in innerClass.enumConstants) {
        if (MuzzleFlag.convertOtelFlag(innerClass.simpleName, enumConstant.name()) < 0) {
          missingFlags += "${innerClass.simpleName}.${enumConstant.name()}"
        }
      }
    }

    then:
    missingFlags == []
  }
}
