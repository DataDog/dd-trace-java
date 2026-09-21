package datadog.trace.agent.tooling.advice;

import static java.util.stream.Collectors.toList;
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
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
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
    List<Usage> typeUsages =
        root.getUsages().stream()
            .filter(
                use ->
                    use.getKind() == UsageKind.TYPE
                        && use.getOwner().equals(Dependency.class.getName()))
            .collect(toList());
    assertEquals(
        Arrays.asList(Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY, Opcodes.LDC),
        typeUsages.stream().map(Usage::getOpcode).collect(toList()));
    assertEquals(
        Arrays.asList(null, null, Type.getDescriptor(Dependency[][].class), null),
        typeUsages.stream().map(Usage::getDescriptor).collect(toList()));
    assertTrue(
        typeUsages.stream()
            .allMatch(
                use ->
                    use.getSource().getClassName().equals(AdviceRoot.class.getName())
                        && use.getSource().getLine() > 0));

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
  void discoversInvokeDynamicAndCatchTypeDependencies() {
    String adviceClass = "generated.Advice";
    class GeneratedAdviceModule extends InstrumenterModule implements Instrumenter.HasMethodAdvice {
      GeneratedAdviceModule() {
        super("generated-advice");
      }

      @Override
      public void methodAdvice(MethodTransformer transformer) {
        transformer.applyAdvice(null, adviceClass);
      }
    }

    AdviceScanResult result =
        AdviceScanner.scan(
            new GeneratedAdviceModule(),
            ClassFileLocator.Simple.of(adviceClass, generatedAdvice(adviceClass)));

    for (String dependency :
        Arrays.asList(
            "callsite.Only",
            "bootstrap.Only",
            "bootstrapArgument.Only",
            "handle.Only",
            "catch.Only")) {
      assertNotNull(result.getClassInfo(dependency), dependency);
    }
  }

  @Test
  void skipsMissingTransitiveInstrumentationClasses() {
    ClassFileLocator delegate = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
    ClassFileLocator locator =
        new ClassFileLocator() {
          @Override
          public Resolution locate(String name) throws IOException {
            return ExternalHelper.class.getName().equals(name)
                ? new Resolution.Illegal(name)
                : delegate.locate(name);
          }

          @Override
          public void close() {}
        };
    AdviceScanResult result = AdviceScanner.scan(new ScanModule(), locator);

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

  private static byte[] generatedAdvice(String className) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC,
        className.replace('.', '/'),
        null,
        "java/lang/Object",
        null);

    MethodVisitor method =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "apply", "()V", null, null);
    Label start = new Label();
    Label end = new Label();
    Label handler = new Label();
    Label done = new Label();
    method.visitTryCatchBlock(start, end, handler, "catch/Only");
    method.visitCode();
    method.visitLabel(start);
    method.visitInvokeDynamicInsn(
        "apply",
        "()Lcallsite/Only;",
        new Handle(
            Opcodes.H_INVOKESTATIC, "bootstrap/Owner", "bootstrap", "(Lbootstrap/Only;)V", false),
        Type.getMethodType("()LbootstrapArgument/Only;"),
        new Handle(Opcodes.H_INVOKESTATIC, "handle/Owner", "apply", "(Lhandle/Only;)V", false));
    method.visitInsn(Opcodes.POP);
    method.visitLabel(end);
    method.visitJumpInsn(Opcodes.GOTO, done);
    method.visitLabel(handler);
    method.visitInsn(Opcodes.POP);
    method.visitLabel(done);
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(1, 0);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }
}
