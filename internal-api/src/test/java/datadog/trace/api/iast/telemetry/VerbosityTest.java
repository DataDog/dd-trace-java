package datadog.trace.api.iast.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class VerbosityTest {

  @TableTest({
    "scenario    | verbosity   | debug | info  | mandatory",
    "OFF         | OFF         | false | false | false    ",
    "MANDATORY   | MANDATORY   | false | false | true     ",
    "INFORMATION | INFORMATION | false | true  | true     ",
    "DEBUG       | DEBUG       | true  | true  | true     "
  })
  void testLevelIsEnabled(Verbosity verbosity, boolean debug, boolean info, boolean mandatory) {
    assertEquals(debug, verbosity.isDebugEnabled());
    assertEquals(info, verbosity.isInformationEnabled());
    assertEquals(mandatory, verbosity.isMandatoryEnabled());
  }
}
