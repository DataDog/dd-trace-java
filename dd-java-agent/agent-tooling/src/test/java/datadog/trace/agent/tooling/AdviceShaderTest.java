package datadog.trace.agent.tooling;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AdviceShaderTest {
  private static final String HELPER = "datadog.trace.instrumentation.jms.DatadogMessageListener";

  @TableTest({
    "scenario         | relocation         ",
    "zero hash        | ''                 ",
    "positive hash    | a                  ",
    "minimum int hash | min-hash-7052265601"
  })
  void helperPackageIsAJavaIdentifierForEveryHashSign(String relocation) {
    AdviceShader shader = AdviceShader.with(singletonMap("", relocation));

    assertValidClassName(shader.uniqueHelper(HELPER));
  }

  @Test
  void jakartaJmsHelperNamesAndReferencesMatch() {
    InstrumenterModule module =
        new InstrumenterModule("test-jakarta-jms") {
          @Override
          public Map<String, String> adviceShading() {
            return singletonMap("javax", "jakarta");
          }

          @Override
          public String[] helperClassNames() {
            return new String[] {HELPER};
          }
        };
    AdviceShader shader = AdviceShader.with(module);
    String shadedHelper = shader.uniqueHelper(HELPER);

    assertTrue(module.adviceShading().hashCode() < 0);
    assertValidClassName(shadedHelper);
    assertNotEquals(HELPER, shadedHelper);
    assertEquals(shadedHelper, shader.shadeClassName(HELPER));
    assertEquals(
        "L" + shadedHelper.replace('.', '/') + ";",
        shader.shadeTypeDescriptor("L" + HELPER.replace('.', '/') + ";"));
    assertEquals("jakarta.jms.MessageListener", shader.shadeClassName("javax.jms.MessageListener"));
  }

  private static void assertValidClassName(String className) {
    for (String segment : className.split("\\.")) {
      assertTrue(Character.isJavaIdentifierStart(segment.charAt(0)), className);
      for (int i = 1; i < segment.length(); i++) {
        assertTrue(Character.isJavaIdentifierPart(segment.charAt(i)), className);
      }
    }
  }
}
