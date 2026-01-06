package datadog.trace.bootstrap.aot;

import static datadog.instrument.asm.Opcodes.ACC_ABSTRACT;
import static datadog.instrument.asm.Opcodes.ACC_NATIVE;
import static datadog.instrument.asm.Opcodes.ARETURN;
import static datadog.instrument.asm.Opcodes.ASM9;
import static datadog.instrument.asm.Opcodes.GETSTATIC;
import static datadog.instrument.asm.Opcodes.ICONST_1;
import static datadog.instrument.asm.Opcodes.INVOKEINTERFACE;
import static datadog.instrument.asm.Opcodes.POP;
import static datadog.instrument.asm.Opcodes.POP2;

import datadog.instrument.asm.ClassReader;
import datadog.instrument.asm.ClassVisitor;
import datadog.instrument.asm.ClassWriter;
import datadog.instrument.asm.MethodVisitor;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Workaround a potential AOT bug where {@link datadog.trace.api.interceptor.TraceInterceptor} is
 * mistakenly restored from the system class-loader in production, even though it was visible from
 * the boot class-loader during training, resulting in {@link LinkageError}s.
 *
 * <p>Any call to {@link datadog.trace.api.Tracer#addTraceInterceptor} from application code in the
 * system class-loader appears to trigger this bug. The workaround is to replace these calls during
 * training with opcodes that pop the tracer and argument, and push the expected return value.
 *
 * <p>Likewise, custom {@code TraceInterceptor} return values are replaced with simple placeholders
 * from the boot class-path which are guaranteed not to trigger the AOT bug.
 *
 * <p>Note these transformations are not persisted, so in production the original code is used.
 */
final class TraceApiTransformer implements ClassFileTransformer {
  private static final ClassLoader SYSTEM_CLASS_LOADER = ClassLoader.getSystemClassLoader();

  static final String TRACE_INTERCEPTOR = "datadog/trace/api/interceptor/TraceInterceptor";

  static final String PLACEHOLDER_TRACE_INTERCEPTOR =
      "datadog/trace/bootstrap/aot/PlaceholderTraceInterceptor";

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain pd,
      byte[] bytecode) {

    // workaround only needed in the system class-loader
    if (loader == SYSTEM_CLASS_LOADER) {
      try {
        ClassReader cr = new ClassReader(bytecode);
        ClassWriter cw = new ClassWriter(cr, 0);
        AtomicBoolean modified = new AtomicBoolean();
        cr.accept(new TraceInterceptorPatch(cw, modified), 0);
        // only return something when we've modified the bytecode
        if (modified.get()) {
          return cw.toByteArray();
        }
      } catch (Throwable ignore) {
        // skip this class
      }
    }
    return null; // tells the JVM to keep the original bytecode
  }

  /**
   * Patches certain references to {@code TraceInterceptor} to workaround AOT bug:
   *
   * <ul>
   *   <li>removes direct calls to {@code Tracer.addTraceInterceptor()}
   *   <li>replaces {@code TraceInterceptor} return values with placeholders
   * </ul>
   */
  static final class TraceInterceptorPatch extends ClassVisitor {
    private final AtomicBoolean modified;

    TraceInterceptorPatch(ClassVisitor cv, AtomicBoolean modified) {
      super(ASM9, cv);
      this.modified = modified;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if ((access & (ACC_ABSTRACT | ACC_NATIVE)) == 0) {
        if (descriptor.endsWith(")L" + TRACE_INTERCEPTOR + ";")) {
          mv = new ReturnPatch(mv, modified);
        }
        return new InvokePatch(mv, modified);
      } else {
        return mv; // no need to patch abstract/native methods
      }
    }
  }

  /** Removes direct calls to {@code Tracer.addTraceInterceptor()}. */
  static final class InvokePatch extends MethodVisitor {
    private final AtomicBoolean modified;

    InvokePatch(MethodVisitor mv, AtomicBoolean modified) {
      super(ASM9, mv);
      this.modified = modified;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (INVOKEINTERFACE == opcode
          && "datadog/trace/api/Tracer".equals(owner)
          && "addTraceInterceptor".equals(name)
          && ("(L" + TRACE_INTERCEPTOR + ";)Z").equals(descriptor)) {
        // discard tracer and trace interceptor argument from call stack
        mv.visitInsn(POP2);
        // push substitute return value (true)
        mv.visitInsn(ICONST_1);
        // flag that we've modified the bytecode
        modified.set(true);
      } else {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    }
  }

  /** Replaces custom {@code TraceInterceptor} return values with placeholders. */
  static final class ReturnPatch extends MethodVisitor {
    private final AtomicBoolean modified;

    ReturnPatch(MethodVisitor mv, AtomicBoolean modified) {
      super(ASM9, mv);
      this.modified = modified;
    }

    @Override
    public void visitInsn(int opcode) {
      if (ARETURN == opcode) {
        // discard old return value from call stack
        mv.visitInsn(POP);
        // push our placeholder interceptor instead
        mv.visitFieldInsn(
            GETSTATIC,
            PLACEHOLDER_TRACE_INTERCEPTOR,
            "INSTANCE",
            "L" + PLACEHOLDER_TRACE_INTERCEPTOR + ";");
        mv.visitInsn(ARETURN);
        // flag that we've modified the bytecode
        modified.set(true);
      } else {
        super.visitInsn(opcode);
      }
    }
  }
}
