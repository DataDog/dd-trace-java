package datadog.trace.civisibility.coverage.file.instrumentation;

import java.util.function.Predicate;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CoverageMethodVisitor extends MethodVisitor {
  private final String className;
  private final Predicate<String> instrumentationFilter;

  protected CoverageMethodVisitor(
      MethodVisitor mv, String className, Predicate<String> instrumentationFilter) {
    super(Opcodes.ASM9, mv);
    this.className = className;
    this.instrumentationFilter = instrumentationFilter;
  }

  @Override
  public void visitCode() {
    CoverageUtils.insertCoverageProbe(className, mv);
  }

  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    mv.visitMaxs(maxStack + 2, maxLocals);
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
    if (instrumentationFilter.test(owner)
        && /* do not insert probe if a class accesses its own field */ !className.equals(owner)) {
      CoverageUtils.insertCoverageProbe(owner, mv);
    }
    mv.visitFieldInsn(opcode, owner, name, descriptor);
  }
}
