package datadog.trace.instrumentation.unbescape;

import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.test.junit.utils.config.WithConfig;
import foo.bar.TestEscapeUtilsSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tabletest.junit.TableTest;

@WithConfig(key = IAST_ENABLED, value = "true")
class EscapeUtilsCallSiteTest extends AbstractInstrumentationTest {

  private static final boolean IS_LATEST_DEP_TEST = Boolean.getBoolean("test.dd.latestDepTest");

  @AfterEach
  void resetIastModule() {
    InstrumentationBridge.PROPAGATION = null;
  }

  @TableTest({
    "method     | input                                                           | expected                                                           ",
    "HTML4_XML  | '<htmlTag>\"escape this < </htmlTag>'                           | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'           ",
    "HTML4      | '<htmlTag>\"escape this < </htmlTag>'                           | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'           ",
    "HTML5      | '<htmlTag>\"escape this < </htmlTag>'                           | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'           ",
    "HTML5_XML  | '<htmlTag>\"escape this < </htmlTag>'                           | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'           ",
    "JAVASCRIPT | '<script>function a(){console.log(\"escape this < \")}<script>' | '<script>function a(){console.log(\\\"escape this < \\\")}<script>'"
  })
  void escapeAndPropagate(EscapeMethod method, String input, String expected) {
    PropagationModule module = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(module);

    String result = invoke(method, input);
    String expectedResult = expected;
    // Unbescape 1.1.8 encodes JavaScript angle brackets as hexadecimal escapes.
    if (IS_LATEST_DEP_TEST && method == EscapeMethod.JAVASCRIPT) {
      expectedResult = expectedResult.replace("<", "\\x3C").replace(">", "\\x3E");
    }

    assertEquals(expectedResult, result);
    verify(module).taintStringIfTainted(result, input, false, XSS_MARK);
    verifyNoMoreInteractions(module);
  }

  @ParameterizedTest(name = "{0} with null input")
  @EnumSource(EscapeMethod.class)
  void nullInputDoesNotThrow(EscapeMethod method) {
    PropagationModule module = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(module);

    assertDoesNotThrow(() -> invoke(method, null));
    verifyNoInteractions(module);
  }

  private static String invoke(EscapeMethod method, String input) {
    switch (method) {
      case HTML4_XML:
        return TestEscapeUtilsSuite.escapeHtml4Xml(input);
      case HTML4:
        return TestEscapeUtilsSuite.escapeHtml4(input);
      case HTML5:
        return TestEscapeUtilsSuite.escapeHtml5(input);
      case HTML5_XML:
        return TestEscapeUtilsSuite.escapeHtml5Xml(input);
      case JAVASCRIPT:
        return TestEscapeUtilsSuite.escapeJavaScript(input);
      default:
        throw new IllegalArgumentException("Unknown escape method: " + method);
    }
  }

  private enum EscapeMethod {
    HTML4_XML,
    HTML4,
    HTML5,
    HTML5_XML,
    JAVASCRIPT
  }
}
