package datadog.trace.agent.tooling.muzzle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanResult;
import datadog.trace.agent.tooling.advice.AdviceScanner;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

class ReferenceCreatorConversionTest {
  @Test
  void appliesAdviceShadingOnlyDuringConversion() {
    ShadingModule module = new ShadingModule();
    AdviceScanResult scan = AdviceScanner.scan(module);
    AdviceShader shader = AdviceShader.with(module.adviceShading());

    List<Reference> converted =
        ReferenceCreator.createReferences(scan, scan.getAdviceRoots(), shader);
    Map<String, Reference> references = byName(converted);

    assertTrue(references.containsKey("relocated.library.TestInfo"));
    assertFalse(references.containsKey(TestInfo.class.getName()));
    assertNotNull(scan.getClassInfo(TestInfo.class.getName()));
  }

  private static Map<String, Reference> byName(Collection<Reference> references) {
    Map<String, Reference> result = new LinkedHashMap<>();
    for (Reference reference : references) {
      result.put(reference.className, reference);
    }
    return result;
  }

  public static final class ShadingModule extends InstrumenterModule
      implements Instrumenter.HasMethodAdvice {
    public ShadingModule() {
      super("muzzle-shading");
    }

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(null, ShadingAdvice.class.getName());
    }

    @Override
    public Map<String, String> adviceShading() {
      return Collections.singletonMap("org.junit.jupiter.api", "relocated.library");
    }
  }

  static final class ShadingAdvice {
    static String apply(TestInfo testInfo) {
      return testInfo.getDisplayName();
    }
  }
}
