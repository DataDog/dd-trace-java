package com.datadog.debugger.instrumentation;

import com.datadog.debugger.agent.DebuggerTransformer;
import com.datadog.debugger.agent.LoggingTracking;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class LogOriginInstrumentor implements ClassFileTransformer {

  private final Instrumentation instrumentation;
  private final LoggingTracking loggingTracking;
  private final String logPattern;

  public LogOriginInstrumentor(LoggingTracking loggingTracking, String logPattern) {
    this.instrumentation = loggingTracking.getInstrumentation();
    this.loggingTracking = loggingTracking;
    this.logPattern = logPattern;
  }

  public void instrument() {
    if (loggingTracking.isLogback()) {
      try {
        Class<?> loggerClass = loggingTracking.getLogBackLoggerClass();
        instrumentation.addTransformer(this, true);
        System.out.println("retransforming: " + loggerClass.getTypeName());
        instrumentation.retransformClasses(loggerClass);
        System.out.println("retransformed: " + loggerClass.getTypeName());
        instrumentation.removeTransformer(this);
        System.out.println("removed: " + loggerClass.getTypeName());
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer)
      throws IllegalClassFormatException {
    if (loggingTracking.isLogback()
        && loggingTracking.getLogBackLoggerInternalClassName().equals(className)) {
      System.out.println("instrumenting " + className);
      ClassReader reader = new ClassReader(classfileBuffer);
      ClassNode classNode = new ClassNode();
      reader.accept(classNode, ClassReader.SKIP_FRAMES);
      for (MethodNode methodNode : classNode.methods) {
        if (methodNode.name.equals("buildLoggingEventAndAppend")) {
          InsnList insnList = new InsnList();
          insnList.add(new VarInsnNode(Opcodes.ALOAD, 4)); // msg param
          insnList.add(new LdcInsnNode(logPattern));
          insnList.add(
              new MethodInsnNode(
                  Opcodes.INVOKESTATIC,
                  Types.DEBUGGER_CONTEXT_TYPE.getInternalName(),
                  "getLogOrigin",
                  Type.getMethodDescriptor(Type.VOID_TYPE, Types.STRING_TYPE, Types.STRING_TYPE)));
          methodNode.instructions.insert(insnList);
          System.out.println("instrumented " + className);
          ClassWriter writer = new DebuggerTransformer.SafeClassWriter(loader);
          classNode.accept(writer);
          return writer.toByteArray();
        }
      }
    }
    return null;
  }
}
