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
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.AdviceSuperclass;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.ScanModule;
import datadog.trace.agent.tooling.advice.AdviceScanningHelper.Dependency;
import datadog.trace.instrumentation.testing.ExternalHelper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
    int registrationsBefore = ScanModule.adviceRegistrations;
    AdviceScanResult result = AdviceScanner.scan(new ScanModule());

    assertEquals(registrationsBefore + 1, ScanModule.adviceRegistrations);
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
                        && use.getSource().getSourceFile().equals("AdviceScanningFixtures.java")
                        && use.getSource().getLine() > 0));

    assertFalse(result.getClassInfo(ClassReader.class.getName()).isScanned());
    assertFalse(result.getClassInfo(String.class.getName()).isScanned());
    assertTrue(result.getClassInfo(Dependency.class.getName()).isScanned());
    assertTrue(result.getClassInfo(AdditionalAdvice.class.getName()).isScanned());
    assertTrue(result.getClassInfo(ExternalHelper.class.getName()).isScanned());
  }

  @Test
  void discoversEnclosingHelpersButNotEnclosingAdviceClasses() {
    AdviceScanResult result = AdviceScanner.scan(new ScanModule());
    String enclosingHelper = AdviceScanningHelper.class.getName();

    assertTrue(result.getClassInfo(enclosingHelper).isScanned());
    for (Class<?> nested :
        new Class<?>[] {
          Dependency.class, AdviceScanningHelper.localClass(), AdviceScanningHelper.anonymousClass()
        }) {
      assertTrue(
          result
              .getClassInfo(nested.getName())
              .getRequiredDependencies()
              .contains(enclosingHelper));
    }
    assertFalse(result.getClasses().containsKey(AdviceScanningFixtures.class.getName()));
  }

  @Test
  void producesDeterministicResults() {
    AdviceScanResult first = AdviceScanner.scan(new ScanModule());
    AdviceScanResult second = AdviceScanner.scan(new ScanModule());

    assertEquals(first.getClasses().keySet(), second.getClasses().keySet());
    assertEquals(first.getAdviceRoots(), second.getAdviceRoots());
  }

  @Test
  void discoversBytecodeOnlyDependencies() {
    AdviceScanResult result = scanGeneratedAdvice(null);

    for (String dependency :
        Arrays.asList(
            "callsite.Only",
            "bootstrap.Only",
            "bootstrapArgument.Only",
            "handle.Only",
            "array.Only",
            "multidimensional.Only",
            "field.Only",
            "fieldHandle.Only",
            "catch.Only")) {
      assertNotNull(result.getClassInfo(dependency), dependency);
    }
    assertFalse(result.getClasses().keySet().stream().anyMatch(name -> name.startsWith("[")));
    assertFalse(result.getClasses().containsKey("int"));
  }

  @Test
  void recordsSourceFilesForHeaderAndMethodUsages() {
    ClassInfo advice =
        scanGeneratedAdvice("DifferentSource.groovy").getClassInfo("generated.Advice");

    assertTrue(advice.getUsages().get(0).isImplementedInterface());
    for (Usage usage : advice.getUsages()) {
      assertEquals("generated.Advice", usage.getSource().getClassName());
      assertEquals("DifferentSource.groovy", usage.getSource().getSourceFile());
      assertEquals(usage.isImplementedInterface() ? -1 : 42, usage.getSource().getLine());
    }
  }

  @Test
  void fallsBackToClassNameWhenSourceFileIsMissing() {
    ClassInfo advice = scanGeneratedAdvice(null).getClassInfo("generated.Advice");

    assertFalse(advice.getUsages().isEmpty());
    for (Usage usage : advice.getUsages()) {
      assertEquals("generated.Advice", usage.getSource().getSourceFile());
    }
  }

  @Test
  void usesProvidedClassLoader() {
    List<String> resources = new ArrayList<>();
    ClassLoader loader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public InputStream getResourceAsStream(String name) {
            resources.add(name);
            return super.getResourceAsStream(name);
          }
        };

    AdviceScanResult result = AdviceScanner.scan(new ScanModule(), loader);

    assertTrue(result.getClassInfo(AdviceRoot.class.getName()).isScanned());
    assertTrue(resources.contains(AdviceRoot.class.getName().replace('.', '/') + ".class"));
  }

  @Test
  void retainsNonObjectSuperclassConstructorRequirements() {
    AdviceScanResult result = AdviceScanner.scan(new ScanModule());
    ClassInfo root = result.getClassInfo(AdviceRoot.class.getName());

    assertNotNull(result.getClassInfo(AdviceSuperclass.class.getName()));
    assertTrue(
        root.getUsages().stream()
            .anyMatch(
                usage ->
                    usage.getKind() == UsageKind.METHOD
                        && usage.getOpcode() == Opcodes.INVOKESPECIAL
                        && usage.getOwner().equals(AdviceSuperclass.class.getName())
                        && usage.getName().equals("<init>")
                        && usage.getDescriptor().equals("(Ljava/lang/String;)V")));
  }

  @Test
  void returnsImmutableResults() {
    AdviceScanResult result = AdviceScanner.scan(new ScanModule());

    assertThrows(UnsupportedOperationException.class, () -> result.getClasses().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.getAdviceRoots().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.getClassInfo(AdviceRoot.class.getName()).getUsages().clear());
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
    assertTrue(result.getClassInfo(Dependency.class.getName()).isScanned());
    ClassInfo helper = result.getClassInfo(ExternalHelper.class.getName());
    assertFalse(helper.isScanned());
  }

  @Test
  void failsWhenModuleOutputHelperIsMissing() {
    ClassFileLocator delegate = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
    ClassFileLocator locator =
        new ClassFileLocator() {
          @Override
          public Resolution locate(String name) throws IOException {
            return Dependency.class.getName().equals(name)
                ? new Resolution.Illegal(name)
                : delegate.locate(name);
          }

          @Override
          public void close() {}
        };
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> AdviceScanner.scan(new ScanModule(), locator));

    assertTrue(error.getMessage().endsWith("helper class from module output is missing"));
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

  private static AdviceScanResult scanGeneratedAdvice(String sourceFile) {
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

    return AdviceScanner.scan(
        new GeneratedAdviceModule(),
        ClassFileLocator.Simple.of(adviceClass, generatedAdvice(adviceClass, sourceFile)));
  }

  private static byte[] generatedAdvice(String className, String sourceFile) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        className.replace('.', '/'),
        null,
        "java/lang/Object",
        new String[] {"declared/Contract"});
    if (sourceFile != null) {
      writer.visitSource(sourceFile, null);
    }
    writer.visitPermittedSubclass("generated/Subclass");

    MethodVisitor method =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "apply", "()V", null, null);
    Label start = new Label();
    Label end = new Label();
    Label handler = new Label();
    Label done = new Label();
    method.visitTryCatchBlock(start, end, handler, "catch/Only");
    method.visitCode();
    method.visitLabel(start);
    method.visitLineNumber(42, start);
    method.visitInvokeDynamicInsn(
        "apply",
        "()Lcallsite/Only;",
        new Handle(
            Opcodes.H_INVOKESTATIC, "bootstrap/Owner", "bootstrap", "(Lbootstrap/Only;)V", false),
        Type.getMethodType("()LbootstrapArgument/Only;"),
        new Handle(Opcodes.H_INVOKESTATIC, "handle/Owner", "apply", "(Lhandle/Only;)V", false));
    method.visitInsn(Opcodes.POP);
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL, "[Larray/Only;", "clone", "()Ljava/lang/Object;", false);
    method.visitInsn(Opcodes.POP);
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL, "[[Lmultidimensional/Only;", "clone", "()Ljava/lang/Object;", false);
    method.visitInsn(Opcodes.POP);
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
    method.visitInsn(Opcodes.POP);
    method.visitFieldInsn(Opcodes.GETSTATIC, "field/Owner", "value", "[[Lfield/Only;");
    method.visitInsn(Opcodes.POP);
    method.visitLdcInsn(
        new Handle(
            Opcodes.H_GETSTATIC, "fieldHandle/Owner", "value", "[[LfieldHandle/Only;", false));
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
