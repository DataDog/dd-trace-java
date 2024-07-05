package datadog.trace.civisibility.coverage.instrumentation.store;

import static datadog.trace.api.civisibility.coverage.CoverageBridge.COVERAGE_STORE_FIELD_NAME;

import datadog.trace.api.civisibility.coverage.CoverageBridge;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A {@link ClassVisitor} that injects an instance field named {@link
 * CoverageBridge#COVERAGE_STORE_FIELD_NAME} of type {@link Object} into the visited class
 */
public class CoverageStoreFieldInjector extends ClassVisitor {

  public CoverageStoreFieldInjector(ClassVisitor cv) {
    super(Opcodes.ASM9, cv);
  }

  @Override
  public void visitEnd() {
    cv.visitField(Opcodes.ACC_PUBLIC, COVERAGE_STORE_FIELD_NAME, "Ljava/lang/Object;", null, null)
        .visitEnd();
    super.visitEnd();
  }
}
