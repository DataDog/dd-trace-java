package datadog.trace.civisibility.coverage.file.instrumentation;

import java.util.function.Predicate;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CoverageClassVisitor extends ClassVisitor {

  private final Predicate<String> instrumentationFilter;
  private String className;

  protected CoverageClassVisitor(ClassVisitor cv, Predicate<String> instrumentationFilter) {
    super(Opcodes.ASM9, cv);
    this.instrumentationFilter = instrumentationFilter;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    this.className = name;
    cv.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
    return new CoverageMethodVisitor(mv, className, instrumentationFilter);
  }
}
