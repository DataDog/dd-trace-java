// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

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
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * ASM visitor wrapper that wraps each supported {@code Thread.sleep(...)} and {@code
 * TimeUnit.sleep(long)} call site in a TaskBlock capture/finish pair.
 *
 * <p>{@code COMPUTE_FRAMES} is requested via {@link #mergeWriter} so ASM recomputes stack-map
 * frames after the added synthetic locals and try-finally handler. We pair it with {@code
 * ClassReader.EXPAND_FRAMES} on the reader side — without that, ASM refuses to recompute frames on
 * class files that already carry stack maps (Java 7+, bytecode v51+), throwing {@code
 * IllegalStateException: ClassReader.accept() should be called with EXPAND_FRAMES flag}.
 */
public final class ThreadSleepRewritingVisitor implements AsmVisitorWrapper {

  @Override
  public int mergeWriter(final int flags) {
    return flags | ClassWriter.COMPUTE_FRAMES;
  }

  @Override
  public int mergeReader(final int flags) {
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
    return new ThreadSleepClassVisitor(classVisitor, typePool);
  }

  /** Per-class visitor: dispatches per-method rewriting. */
  static final class ThreadSleepClassVisitor extends ClassVisitor {
    private final TypePool typePool;

    ThreadSleepClassVisitor(final ClassVisitor cv, final TypePool typePool) {
      super(OpenedClassReader.ASM_API, cv);
      this.typePool = typePool;
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      final MethodVisitor delegate =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      if (delegate == null) {
        return null;
      }
      return new ThreadSleepCallSiteMethodVisitor(access, descriptor, delegate, typePool);
    }
  }
}
