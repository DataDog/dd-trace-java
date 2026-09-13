package datadog.trace.instrumentation.robolectric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class RobolectricTestAnnotatorTest {

  @TableTest({
    "scenario       | versionCodeName   | codename",
    "major release  | JELLY_BEAN        | J       ",
    "Jelly Bean MR1 | JELLY_BEAN_MR1    | JMR1    ",
    "Jelly Bean MR2 | JELLY_BEAN_MR2    | JMR2    ",
    "Lollipop MR1   | LOLLIPOP_MR1      | LMR1    ",
    "Nougat MR1     | N_MR1             | NMR1    ",
    "Oreo MR1       | O_MR1             | OMR1    ",
    "Snow Cone v2   | S_V2              | Sv2     ",
    "single word    | TIRAMISU          | T       ",
    "multi-word U   | UPSIDE_DOWN_CAKE  | U       ",
    "multi-word V   | VANILLA_ICE_CREAM | V       "
  })
  void derivesCodename(String versionCodeName, String codename) {
    assertEquals(codename, RobolectricTestAnnotator.androidCodename(versionCodeName));
  }
}
