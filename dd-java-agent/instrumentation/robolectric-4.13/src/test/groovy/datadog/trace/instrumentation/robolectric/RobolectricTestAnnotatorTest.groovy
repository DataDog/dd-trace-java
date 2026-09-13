package datadog.trace.instrumentation.robolectric

import spock.lang.Specification

class RobolectricTestAnnotatorTest extends Specification {

  def "derives #codename from #versionCodeName"() {
    expect:
    RobolectricTestAnnotator.androidCodename(versionCodeName) == codename

    where:
    versionCodeName     | codename
    "JELLY_BEAN"        | "J"
    "JELLY_BEAN_MR1"    | "JMR1"
    "JELLY_BEAN_MR2"    | "JMR2"
    "LOLLIPOP_MR1"      | "LMR1"
    "N_MR1"             | "NMR1"
    "O_MR1"             | "OMR1"
    "S_V2"              | "Sv2"
    "TIRAMISU"          | "T"
    "UPSIDE_DOWN_CAKE"  | "U"
    "VANILLA_ICE_CREAM" | "V"
  }
}
