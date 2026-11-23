package datadog.trace.api.iast.securitycontrol;

import static datadog.trace.api.iast.VulnerabilityMarks.COMMAND_INJECTION_MARK;
import static datadog.trace.api.iast.VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK;
import static datadog.trace.api.iast.VulnerabilityMarks.SQL_INJECTION_MARK;
import static datadog.trace.api.iast.securitycontrol.SecurityControlType.INPUT_VALIDATOR;
import static datadog.trace.api.iast.securitycontrol.SecurityControlType.SANITIZER;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SecurityControlFormatterTest {

  private static final String INPUT_VALIDATOR_CLASS = "bar/foo/CustomInputValidator";
  private static final String SANITIZER_CLASS = "bar/foo/CustomSanitizer";

  @Test
  void testSimpleInputValidator() {
    String config = "INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(1, result.size());
    List<SecurityControl> securityControls = result.get(INPUT_VALIDATOR_CLASS);
    assertEquals(1, securityControls.size());
    SecurityControl securityControl = securityControls.get(0);
    assertEquals(INPUT_VALIDATOR, securityControl.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, securityControl.getMarks());
    assertEquals(INPUT_VALIDATOR_CLASS, securityControl.getClassName());
    assertEquals("validate", securityControl.getMethod());
    assertNull(securityControl.getParameterTypes());
    assertNull(securityControl.getParametersToMark());
  }

  @Test
  void testSimpleSanitizer() {
    String config = "SANITIZER:COMMAND_INJECTION:bar.foo.CustomSanitizer:sanitize";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(1, result.size());
    List<SecurityControl> securityControls = result.get(SANITIZER_CLASS);
    assertEquals(1, securityControls.size());
    SecurityControl securityControl = securityControls.get(0);
    assertEquals(SANITIZER, securityControl.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, securityControl.getMarks());
    assertEquals(SANITIZER_CLASS, securityControl.getClassName());
    assertEquals("sanitize", securityControl.getMethod());
    assertNull(securityControl.getParameterTypes());
    assertNull(securityControl.getParametersToMark());
  }

  @Test
  void testMultipleSecurityControls() {
    String config =
        "INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate;SANITIZER:COMMAND_INJECTION:bar.foo.CustomSanitizer:sanitize";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(2, result.size());
    SecurityControl inputValidator = result.get(INPUT_VALIDATOR_CLASS).get(0);
    assertEquals(INPUT_VALIDATOR, inputValidator.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, inputValidator.getMarks());
    assertEquals(INPUT_VALIDATOR_CLASS, inputValidator.getClassName());
    assertEquals("validate", inputValidator.getMethod());
    assertNull(inputValidator.getParameterTypes());
    assertNull(inputValidator.getParametersToMark());

    SecurityControl sanitizer = result.get(SANITIZER_CLASS).get(0);
    assertEquals(SANITIZER, sanitizer.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, sanitizer.getMarks());
    assertEquals(SANITIZER_CLASS, sanitizer.getClassName());
    assertEquals("sanitize", sanitizer.getMethod());
    assertNull(sanitizer.getParameterTypes());
    assertNull(sanitizer.getParametersToMark());
  }

  @Test
  void testMultipleSecureMarks() {
    String config =
        "INPUT_VALIDATOR:COMMAND_INJECTION,SQL_INJECTION:bar.foo.CustomInputValidator:validate";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(1, result.size());
    SecurityControl securityControl = result.get(INPUT_VALIDATOR_CLASS).get(0);
    assertEquals(INPUT_VALIDATOR, securityControl.getType());
    assertEquals(
        COMMAND_INJECTION_MARK | SQL_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK,
        securityControl.getMarks());
    assertEquals(INPUT_VALIDATOR_CLASS, securityControl.getClassName());
    assertEquals("validate", securityControl.getMethod());
    assertNull(securityControl.getParameterTypes());
    assertNull(securityControl.getParametersToMark());
  }

  @Test
  void testOverchargedMethods() {
    String config =
        "INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:java.lang.Object,java.lang.String,java.lang.String";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(1, result.size());
    SecurityControl securityControl = result.get(INPUT_VALIDATOR_CLASS).get(0);
    assertEquals(INPUT_VALIDATOR, securityControl.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, securityControl.getMarks());
    assertEquals(INPUT_VALIDATOR_CLASS, securityControl.getClassName());
    assertEquals("validate", securityControl.getMethod());
    assertEquals(
        asList("java.lang.Object", "java.lang.String", "java.lang.String"),
        securityControl.getParameterTypes());
    assertNull(securityControl.getParametersToMark());
  }

  @Test
  void testParametersToMark() {
    String config = "INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:1,2";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(1, result.size());
    SecurityControl securityControl = result.get(INPUT_VALIDATOR_CLASS).get(0);
    assertEquals(INPUT_VALIDATOR, securityControl.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, securityControl.getMarks());
    assertEquals(INPUT_VALIDATOR_CLASS, securityControl.getClassName());
    assertEquals("validate", securityControl.getMethod());
    assertNull(securityControl.getParameterTypes());
    BitSet parametersToMark = securityControl.getParametersToMark();
    assertEquals(2, parametersToMark.cardinality());
    assertTrue(parametersToMark.get(1));
    assertTrue(parametersToMark.get(2));
  }

  @Test
  void testOverchargedMethodsWithParametersToMark() {
    String config =
        "INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:java.lang.Object,java.lang.String,java.lang.String:1,2";
    Map<String, List<SecurityControl>> result = SecurityControlFormatter.format(config);

    assertEquals(1, result.size());
    SecurityControl securityControl = result.get(INPUT_VALIDATOR_CLASS).get(0);
    assertEquals(INPUT_VALIDATOR, securityControl.getType());
    assertEquals(COMMAND_INJECTION_MARK | CUSTOM_SECURITY_CONTROL_MARK, securityControl.getMarks());
    assertEquals(INPUT_VALIDATOR_CLASS, securityControl.getClassName());
    assertEquals("validate", securityControl.getMethod());
    assertEquals(
        asList("java.lang.Object", "java.lang.String", "java.lang.String"),
        securityControl.getParameterTypes());
    BitSet parametersToMark = securityControl.getParametersToMark();
    assertEquals(2, parametersToMark.cardinality());
    assertTrue(parametersToMark.get(1));
    assertTrue(parametersToMark.get(2));
  }

  @TableTest({
    "scenario                        | config                                                                                                                          ",
    "empty                           | ''                                                                                                                              ",
    "not a configuration             | 'This is not a valid configuration'                                                                                             ",
    "type only                       | 'INPUT_VALIDATOR'                                                                                                               ",
    "missing class                   | 'INPUT_VALIDATOR:COMMAND_INJECTION'                                                                                             ",
    "missing method                  | 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator'                                                                ",
    "parameters to mark before types | 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:1,2:java.lang.Object,java.lang.String,java.lang.String'"
  })
  void testErrorControl(String config) {
    Map<String, List<SecurityControl>> result =
        assertDoesNotThrow(() -> SecurityControlFormatter.format(config));

    assertNull(result);
  }
}
