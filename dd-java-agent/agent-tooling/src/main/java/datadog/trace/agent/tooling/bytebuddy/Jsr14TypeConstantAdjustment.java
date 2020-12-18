package datadog.trace.agent.tooling.bytebuddy;

import static net.bytebuddy.ClassFileVersion.JAVA_V5;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.TypeConstantAdjustment;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/**
 * Extends Byte-Buddy's {@link TypeConstantAdjustment} to null out generic signatures when the
 * classfile version is 1.4 or below. This fixes issues instrumenting classes compiled using the
 * short-lived 'jsr14' target, which have a nominal class version of 48 (1.4) but also contain
 * generic signatures not found in 1.4 classes.
 *
 * <p>This has been observed in older OSGi containers that used the 'jsr14' target to help with
 * migration to generic APIs while allowing the same classes to run on older pre-generic JVMs.
 *
 * <p>If generic signatures are left in place then Byte-Buddy refuses to instrument the class
 * because it thinks we are trying to add Java 5+ advice to a Java 1.4 class, even though the
 * generic signatures exist in the original class.
 */
enum Jsr14TypeConstantAdjustment implements AsmVisitorWrapper {
  INSTANCE;

  @Override
  public int mergeWriter(final int flags) {
    return flags;
  }

  @Override
  public int mergeReader(final int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      final TypeDescription instrumentedType,
      final ClassVisitor classVisitor,
      final Implementation.Context implementationContext,
      final TypePool typePool,
      final FieldList<FieldDescription.InDefinedShape> fields,
      final MethodList<?> methods,
      final int writerFlags,
      final int readerFlags) {

    final ClassVisitor typeConstantAdjuster =
        TypeConstantAdjustment.INSTANCE.wrap(
            instrumentedType,
            classVisitor,
            implementationContext,
            typePool,
            fields,
            methods,
            writerFlags,
            readerFlags);

    if (implementationContext.getClassFileVersion().isAtLeast(JAVA_V5)) {
      return typeConstantAdjuster;
    }

    // null-out generic signatures so Byte-Buddy can instrument this class - these signatures don't
    // exist in normal 1.4 class files, but might be in the class file if it was compiled using the
    // javac target of 'jsr14'

    return new ClassVisitor(Opcodes.ASM7, typeConstantAdjuster) {
      @Override
      public void visit(
          final int version,
          final int access,
          final String name,
          final String signature,
          final String superName,
          final String[] interfaces) {
        super.visit(version, access, name, null, superName, interfaces);
      }

      @Override
      public FieldVisitor visitField(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final Object value) {
        return super.visitField(access, name, descriptor, null, value);
      }

      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        return super.visitMethod(access, name, descriptor, null, exceptions);
      }
    };
  }
}
