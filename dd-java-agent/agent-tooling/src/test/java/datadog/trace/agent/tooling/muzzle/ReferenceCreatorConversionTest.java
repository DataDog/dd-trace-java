package datadog.trace.agent.tooling.muzzle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanResult;
import datadog.trace.agent.tooling.advice.AdviceScanner;
import datadog.trace.instrumentation.testing.ExternalHelper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

class ReferenceCreatorConversionTest {
  @Test
  void preservesTransitiveReferencesThroughArrayOwners() {
    for (Class<?> advice :
        new Class<?>[] {ArrayCloneAdvice.class, MultidimensionalArrayCloneAdvice.class}) {
      Map<String, Reference> references = ReferenceCreatorTestSupport.referencesFrom(advice);

      Reference type = references.get(Type.class.getName());
      assertNotNull(type, advice.getName());
      assertTrue(
          Stream.of(type.methods)
              .anyMatch(
                  method ->
                      method.name.equals("getType")
                          && method.methodType.equals(
                              "(Ljava/lang/Class;)Lnet/bytebuddy/jar/asm/Type;")),
          advice.getName());
      assertFalse(references.keySet().stream().anyMatch(name -> name.startsWith("[")));
    }
  }

  @Test
  void ignoresPrimitiveArrayOwners() {
    assertTrue(
        ReferenceCreatorTestSupport.referencesFrom(PrimitiveArrayCloneAdvice.class).isEmpty());
  }

  @Test
  void appliesAdviceShadingOnlyDuringConversion() {
    ShadingModule module = new ShadingModule();
    AdviceScanResult scan = AdviceScanner.scan(module);
    AdviceShader shader = AdviceShader.with(module.adviceShading());

    List<Reference> converted = ReferenceCreator.createReferences(scan, shader);
    Map<String, Reference> references = ReferenceCreatorTestSupport.byName(converted);

    assertTrue(references.containsKey("relocated.library.TestInfo"));
    assertFalse(references.containsKey(TestInfo.class.getName()));
    assertFalse(references.keySet().stream().anyMatch(name -> name.startsWith("[")));
    assertNotNull(scan.getClassInfo(TestInfo.class.getName()));
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

  static final class ArrayCloneAdvice {
    static Object apply(ExternalHelper[] helpers) {
      return helpers.clone();
    }
  }

  static final class MultidimensionalArrayCloneAdvice {
    static Object apply(ExternalHelper[][] helpers) {
      return helpers.clone();
    }
  }

  static final class PrimitiveArrayCloneAdvice {
    static Object apply(int[] values) {
      return values.clone();
    }
  }

  static final class ShadingAdvice {
    static String apply(TestInfo testInfo) {
      TestInfo[] testInfos = {testInfo};
      return testInfos.clone()[0].getDisplayName();
    }
  }
}
