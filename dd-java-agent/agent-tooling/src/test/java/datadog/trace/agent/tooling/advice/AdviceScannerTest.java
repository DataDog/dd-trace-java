package datadog.trace.agent.tooling.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanResult.ClassInfo;
import datadog.trace.agent.tooling.advice.AdviceScanResult.Usage;
import datadog.trace.agent.tooling.advice.AdviceScanResult.UsageKind;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.AdditionalAdvice;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.AdviceRoot;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.Dependency;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.ScanModule;
import datadog.trace.instrumentation.testing.ExternalHelper;
import java.io.InputStream;
import java.util.Arrays;
import net.bytebuddy.jar.asm.ClassReader;
import org.junit.jupiter.api.Test;

class AdviceScannerTest {
  @Test
  void scansNeutralUsesAndAdditionalClasses() {
    AdviceScanResult result = AdviceScanner.scan(new ScanModule());

    assertEquals(
        Arrays.asList(AdviceRoot.class.getName(), AdditionalAdvice.class.getName()),
        result.getAdviceRoots());
    ClassInfo root = result.getClassInfo(AdviceRoot.class.getName());
    assertTrue(root.isScanned());
    assertTrue(hasUsage(root, UsageKind.FIELD, "field"));
    assertTrue(hasUsage(root, UsageKind.METHOD, "<init>"));
    assertTrue(hasUsage(root, UsageKind.METHOD, "method"));
    assertTrue(hasUsage(root, UsageKind.TYPE, null));

    Usage invokeDynamic = firstUsage(root, UsageKind.INVOKEDYNAMIC);
    assertNotNull(invokeDynamic);
    assertFalse(invokeDynamic.getHandles().isEmpty());
    assertTrue(
        root.getUsages().stream()
            .anyMatch(
                use ->
                    use.getKind() == UsageKind.TYPE
                        && use.getOwner().equals(Dependency.class.getName())));

    assertFalse(result.getClassInfo(ClassReader.class.getName()).isScanned());
    assertFalse(result.getClassInfo(String.class.getName()).isScanned());
    assertFalse(result.getClassInfo(Dependency.class.getName()).isScanned());
    assertTrue(result.getClassInfo(AdditionalAdvice.class.getName()).isScanned());
    assertTrue(result.getClassInfo(ExternalHelper.class.getName()).isScanned());
  }

  @Test
  void producesDeterministicResults() {
    AdviceScanResult first = AdviceScanner.scan(new ScanModule());
    AdviceScanResult second = AdviceScanner.scan(new ScanModule());

    assertEquals(first.getClasses().keySet(), second.getClasses().keySet());
    assertEquals(first.getAdviceRoots(), second.getAdviceRoots());
  }

  @Test
  void skipsMissingTransitiveInstrumentationClasses() {
    String missingResource = ExternalHelper.class.getName().replace('.', '/') + ".class";
    ClassLoader loader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public InputStream getResourceAsStream(String name) {
            return missingResource.equals(name) ? null : super.getResourceAsStream(name);
          }
        };
    AdviceScanResult result = AdviceScanner.scan(new ScanModule(), loader);

    ClassInfo root = result.getClassInfo(AdviceRoot.class.getName());
    assertTrue(root.isScanned());
    assertTrue(hasUsage(root, UsageKind.METHOD, "method"));
    assertFalse(result.getClassInfo(Dependency.class.getName()).isScanned());
    ClassInfo helper = result.getClassInfo(ExternalHelper.class.getName());
    assertFalse(helper.isScanned());
  }

  @Test
  void failsWhenAdviceRootIsMissing() {
    class MissingAdviceModule extends InstrumenterModule implements Instrumenter.HasMethodAdvice {
      MissingAdviceModule() {
        super("missing-advice");
      }

      @Override
      public void methodAdvice(MethodTransformer transformer) {
        transformer.applyAdvice(null, "missing.Advice");
      }
    }

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> AdviceScanner.scan(new MissingAdviceModule()));
    assertEquals(
        "Advice scan failed for module "
            + MissingAdviceModule.class.getName()
            + ", advice missing.Advice, class missing.Advice: advice class is missing",
        error.getMessage());
  }

  private static boolean hasUsage(ClassInfo info, UsageKind kind, String name) {
    return info.getUsages().stream()
        .anyMatch(use -> use.getKind() == kind && (name == null || name.equals(use.getName())));
  }

  private static Usage firstUsage(ClassInfo info, UsageKind kind) {
    return info.getUsages().stream().filter(use -> use.getKind() == kind).findFirst().orElse(null);
  }
}
