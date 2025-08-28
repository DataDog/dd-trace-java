package datadog.trace.agent.tooling.classinject;

import static net.bytebuddy.jar.asm.Opcodes.*;

import datadog.environment.JavaVirtualMachine;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;

/**
 * Supports injection of auxiliary classes, even in the bootstrap class-loader.
 *
 * <p>Uses {@link Instrumentation} to access {@code ClassLoader.defineClass} without reflection.
 *
 * <ul>
 *   <li>To use this feature, first call {@link #enableClassInjection}
 *   <li>To inject a class call {@link #injectClass} with the target class-loader
 *   <li>Use {@link #injectBootClass} to inject classes into the bootstrap class-loader
 *   <li>The API also supports injecting classes using a custom {@link ProtectionDomain}
 * </ul>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ClassInjector {

  /** Injects classes in the bootstrap class-loader. */
  public static List<Class<?>> injectBootClass(Map<String, byte[]> bytecode) {
    return (List<Class<?>>) classDefiner().apply(bytecode, null);
  }

  /** Injects classes in the specified class-loader. */
  public static List<Class<?>> injectClass(Map<String, byte[]> bytecode, ClassLoader cl) {
    return (List<Class<?>>) classDefiner().apply(bytecode, cl);
  }

  /** Injects classes using the given protection domain. */
  public static List<Class<?>> injectClass(Map<String, byte[]> bytecode, ProtectionDomain pd) {
    return (List<Class<?>>) classDefiner().apply(bytecode, pd);
  }

  private static BiFunction classDefiner() {
    if (classDefiner == null) {
      throw new UnsupportedOperationException("Class injection not enabled");
    }
    return classDefiner;
  }

  private static volatile BiFunction classDefiner;

  public static void enableClassInjection(Instrumentation inst) {
    try {
      InjectGlue injectGlue = new InjectGlue();
      try {
        // temporary transformation to install our glue to access ClassLoader.defineClass
        inst.addTransformer(injectGlue, true);
        inst.retransformClasses(ClassLoader.class);
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        classDefiner = (BiFunction) cl.loadClass(DefineClassGlue.ID).newInstance();
      } finally {
        inst.removeTransformer(injectGlue);
        inst.retransformClasses(ClassLoader.class);
      }
    } catch (Throwable e) {
      throw new UnsupportedOperationException("Class injection not available", e);
    }
  }

  static final class InjectGlue implements ClassFileTransformer {
    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] bytecode) {
      if ("java/lang/ClassLoader".equals(className)) {
        ClassReader cr = new ClassReader(bytecode);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassLoaderPatch(cw), 0);
        return cw.toByteArray();
      } else {
        return null;
      }
    }
  }

  static final class ClassLoaderPatch extends ClassVisitor {
    ClassLoaderPatch(ClassVisitor cv) {
      super(ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
      // hook into both forms of the 'loadClass' method to retrieve the injected glue
      // custom system class-loaders will call one of them to fetch bootstrap classes
      if ((access & ACC_STATIC) == 0
          && "loadClass".equals(name)
          && descriptor.startsWith("(Ljava/lang/String;")) {
        return new LoadClassPatch(mv);
      }
      return mv;
    }
  }

  static final class LoadClassPatch extends MethodVisitor {
    LoadClassPatch(MethodVisitor mv) {
      super(ASM9, mv);
    }

    @Override
    public void visitCode() {
      mv.visitCode();

      Label notDatadogGlueRequest = new Label();

      // add branch at start of loadClass method to define our glue as a hidden/anonymous class
      mv.visitLdcInsn(DefineClassGlue.ID);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
      mv.visitJumpInsn(IFEQ, notDatadogGlueRequest);

      if (JavaVirtualMachine.isJavaVersionAtLeast(15)) {
        // on Java 15+ prepare MethodHandles.lookup()
        mv.visitMethodInsn(
            INVOKESTATIC,
            "java/lang/invoke/MethodHandles",
            "lookup",
            "()Ljava/lang/invoke/MethodHandles$Lookup;",
            false);
        mv.visitLdcInsn(DefineClassGlue.V9);
      } else if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
        // on Java 9+ prepare jdk.internal.misc.Unsafe
        mv.visitMethodInsn(
            INVOKESTATIC,
            "jdk/internal/misc/Unsafe",
            "getUnsafe",
            "()Ljdk/internal/misc/Unsafe;",
            false);
        mv.visitLdcInsn(Type.getType(ClassLoader.class));
        mv.visitLdcInsn(DefineClassGlue.V9);
      } else {
        // on Java 8 prepare sun.misc.Unsafe
        mv.visitMethodInsn(
            INVOKESTATIC, "sun/misc/Unsafe", "getUnsafe", "()Lsun/misc/Unsafe;", false);
        mv.visitLdcInsn(Type.getType(ClassLoader.class));
        mv.visitLdcInsn(DefineClassGlue.V8);
      }

      // unpack the UTF-16BE encoded string back into bytecode
      mv.visitFieldInsn(
          GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_16BE", "Ljava/nio/charset/Charset;");
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", false);

      if (JavaVirtualMachine.isJavaVersionAtLeast(15)) {
        // on Java 15+ use MethodHandles.lookup().defineHiddenClass(...)
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/invoke/MethodHandles$Lookup$ClassOption");
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandles$Lookup",
            "defineHiddenClass",
            "([BZ[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)Ljava/lang/invoke/MethodHandles$Lookup;",
            false);
        // use lookupClass() to retrieve the hidden class we just defined
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandles$Lookup",
            "lookupClass",
            "()Ljava/lang/Class;",
            false);
      } else if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
        // on Java 9+ use jdk.internal.misc.Unsafe.defineAnonymousClass(...)
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "jdk/internal/misc/Unsafe",
            "defineAnonymousClass",
            "(Ljava/lang/Class;[B[Ljava/lang/Object;)Ljava/lang/Class;",
            false);
      } else {
        // on Java 8 use sun.misc.Unsafe.defineAnonymousClass(...)
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "sun/misc/Unsafe",
            "defineAnonymousClass",
            "(Ljava/lang/Class;[B[Ljava/lang/Object;)Ljava/lang/Class;",
            false);
      }

      mv.visitInsn(ARETURN);

      // otherwise this is a standard load request, handle it as before
      mv.visitLabel(notDatadogGlueRequest);
      mv.visitFrame(F_SAME, 0, null, 0, null);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      // ensure we have enough stack allocated for our code
      mv.visitMaxs(Math.max(maxStack, 4), maxLocals);
    }
  }
}
