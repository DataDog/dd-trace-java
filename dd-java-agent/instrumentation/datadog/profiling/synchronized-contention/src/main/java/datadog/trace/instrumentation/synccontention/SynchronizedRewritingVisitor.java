package datadog.trace.instrumentation.synccontention;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * ASM visitor wrapper that rewrites JVM {@code MONITORENTER} opcodes and {@code ACC_SYNCHRONIZED}
 * methods to emit {@code datadog.TaskBlock} JFR events around the lock-acquisition wait.
 *
 * <p>Two forms of {@code synchronized} are handled:
 *
 * <ul>
 *   <li>Block-level {@code synchronized(obj) { ... }} &mdash; rewritten by wrapping each {@code
 *       MONITORENTER} opcode with capture/finish calls.
 *   <li>Method-level {@code synchronized} methods &mdash; the {@code ACC_SYNCHRONIZED} flag is
 *       stripped and the lock acquisition is materialized as explicit {@code MONITORENTER}/{@code
 *       MONITOREXIT} opcodes with a try-finally around the original body.
 * </ul>
 *
 * <p>The method-level rewrite intentionally makes the method no longer appear {@code synchronized}
 * through reflection. That is the trade-off for measuring the lock acquisition wait in bytecode:
 * leaving {@code ACC_SYNCHRONIZED} in place would make the JVM acquire the monitor before any
 * helper code can run.
 *
 * <p>{@code COMPUTE_FRAMES} is requested via {@link #mergeWriter} so ASM recomputes stack-map
 * frames after the added synthetic locals.
 *
 * <p>Bridge / synthetic methods are rewritten normally; {@code <init>} and {@code <clinit>} cannot
 * be {@code ACC_SYNCHRONIZED} per JVMS but can contain {@code synchronized(...)} blocks, which we
 * still rewrite.
 */
public final class SynchronizedRewritingVisitor implements AsmVisitorWrapper {

  @Override
  public int mergeWriter(final int flags) {
    return flags | ClassWriter.COMPUTE_FRAMES;
  }

  @Override
  public int mergeReader(final int flags) {
    // COMPUTE_FRAMES on the writer requires EXPAND_FRAMES on the reader for any class file
    // carrying stack maps (Java 7+ / bytecode v51+). Without it ASM throws
    // IllegalStateException: ClassReader.accept() should be called with EXPAND_FRAMES flag,
    // and the class transformer falls back silently, leaving the class un-instrumented.
    return flags | ClassReader.EXPAND_FRAMES;
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
    return new RewritingClassVisitor(classVisitor);
  }

  /** Per-class visitor: tracks the declaring class name and dispatches per-method rewriting. */
  static final class RewritingClassVisitor extends ClassVisitor {
    private String className;

    RewritingClassVisitor(final ClassVisitor cv) {
      super(OpenedClassReader.ASM_API, cv);
    }

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      this.className = name;
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      final boolean isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
      final boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;
      final boolean wasSynchronized = (access & Opcodes.ACC_SYNCHRONIZED) != 0;
      final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

      // Abstract / native methods have no body — nothing to rewrite. (A method declared both
      // ACC_SYNCHRONIZED and ACC_NATIVE keeps its native-monitor semantics; we leave it alone.)
      if (isAbstract || isNative) {
        return super.visitMethod(access, name, descriptor, signature, exceptions);
      }

      // Strip ACC_SYNCHRONIZED so the JVM no longer implicitly locks. The visitor will inject
      // explicit MONITORENTER/MONITOREXIT around the original body.
      final int newAccess = wasSynchronized ? (access & ~Opcodes.ACC_SYNCHRONIZED) : access;

      final MethodVisitor delegate =
          super.visitMethod(newAccess, name, descriptor, signature, exceptions);
      if (delegate == null) {
        return null;
      }
      return new SynchronizedMethodVisitor(
          newAccess, descriptor, delegate, wasSynchronized, isStatic, className);
    }
  }
}
