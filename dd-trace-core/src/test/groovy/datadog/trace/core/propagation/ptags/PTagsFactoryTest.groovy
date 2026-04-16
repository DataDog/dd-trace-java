package datadog.trace.core.propagation.ptags

import datadog.trace.core.test.DDCoreSpecification

class PTagsFactoryTest extends DDCoreSpecification {

  def 'formatKnuthSamplingRate is locale-independent'() {
    setup:
    def originalLocale = Locale.getDefault()

    when:
    Locale.setDefault(Locale.GERMANY)

    then:
    PTagsFactory.PTags.formatKnuthSamplingRate(0.3d)      == "0.3"
    PTagsFactory.PTags.formatKnuthSamplingRate(1.0d)      == "1"
    PTagsFactory.PTags.formatKnuthSamplingRate(0.000001d) == "0.000001"
    PTagsFactory.PTags.formatKnuthSamplingRate(0.0d)      == "0"
    PTagsFactory.PTags.formatKnuthSamplingRate(0.5d)      == "0.5"

    cleanup:
    Locale.setDefault(originalLocale)
  }
}
