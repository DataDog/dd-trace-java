package datadog.trace.api.iast;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

public class CallPseudoAdviceDump implements Opcodes {
  public static byte[] dump() throws Exception {
    ClassWriter cw = new ClassWriter(0);
    MethodVisitor mv;

    cw.visit(
        V1_7,
        ACC_PUBLIC | ACC_SUPER,
        "datadog/trace/api/iast/CallPseudoAdvice",
        null,
        "java/lang/Object",
        new String[] {"datadog/trace/api/iast/HelperInvocationBenchmark$CallPseudoAdviceI"});

    cw.visitInnerClass(
        "datadog/trace/api/iast/HelperInvocationBenchmark$CallPseudoAdviceI",
        "datadog/trace/api/iast/HelperInvocationBenchmark",
        "CallPseudoAdviceI",
        ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

    {
      mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      mv.visitInsn(RETURN);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }
    {
      mv =
          cw.visitMethod(
              ACC_PUBLIC,
              "callStatic",
              "(Lorg/openjdk/jmh/infra/Blackhole;Ljava/lang/String;)V",
              null,
              null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ALOAD, 2);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "datadog/trace/api/iast/HelperInvocationBenchmark$StaticBridge",
          "callHelper",
          "(Lorg/openjdk/jmh/infra/Blackhole;Ljava/lang/String;)V",
          false);
      mv.visitInsn(RETURN);
      mv.visitMaxs(2, 3);
      mv.visitEnd();
    }
    {
      mv =
          cw.visitMethod(
              ACC_PUBLIC,
              "callDynamic",
              "(Lorg/openjdk/jmh/infra/Blackhole;Ljava/lang/String;)V",
              null,
              null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ALOAD, 2);

      Handle handle =
          new Handle(
              Opcodes.H_INVOKESTATIC,
              "datadog/trace/api/iast/InvokeDynamicHelperRegistry",
              "bootstrap",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
              false);
      String helperKey =
          HelperInvocationBenchmark.HelperContainer.class.getName() + "\0" + "helperMeth";
      mv.visitInvokeDynamicInsn(
          "callHelper",
          "(Lorg/openjdk/jmh/infra/Blackhole;Ljava/lang/String;)V",
          handle,
          helperKey);
      mv.visitInsn(RETURN);
      mv.visitMaxs(2, 3);
      mv.visitEnd();
    }
    cw.visitEnd();

    return cw.toByteArray();
  }
}
