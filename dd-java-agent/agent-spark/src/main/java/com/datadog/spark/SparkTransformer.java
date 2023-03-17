package com.datadog.spark;

import datadog.trace.agent.tooling.AgentStrategies;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class SparkTransformer implements ClassFileTransformer {

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer)
      throws IllegalClassFormatException {
    if (className == null) {
      return null;
    }
    if (className.equals("org/apache/spark/SparkContext")) {
      System.err.println("Found SparkContext class");
      ClassReader reader = new ClassReader(classfileBuffer);
      ClassNode classNode = new ClassNode();
      reader.accept(classNode, ClassReader.SKIP_FRAMES);
      for (MethodNode methodNode : classNode.methods) {
        if (methodNode.name.equals("setupAndStartListenerBus")) {
          System.err.println("Found setupAndStartListenerBus method, instrumenting...");
          try {
            instrumentSetupListener(methodNode);
            ClassWriter writer = new SafeClassWriter(loader);
            classNode.accept(writer);
            System.err.println("instrumentation done");
            return writer.toByteArray();
          } catch (Exception ex) {
            ex.printStackTrace(System.err);
          }
        }
      }
    }
    return null;
  }

  private void instrumentSetupListener(MethodNode methodNode) {
    InsnList insnList = new InsnList();
    // stack []
    insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
    // stack [this]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "org/apache/spark/SparkContext",
            "getClass",
            "()Ljava/lang/Class;"));
    // stack [class]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Class",
            "getClassLoader",
            "()Ljava/lang/ClassLoader;"));
    // stack [classloader]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "datadog/trace/bootstrap/instrumentation/spark/SparkAgentContext",
            "register",
            "(Ljava/lang/ClassLoader;)V"));
    // stack []
    insnList.add(
        new FieldInsnNode(
            Opcodes.GETSTATIC,
            "org/apache/spark/util/Utils$",
            "MODULE$",
            "Lorg/apache/spark/util/Utils$;"));
    // stack [utils]
    insnList.add(
        new LdcInsnNode(Type.getObjectType("org/apache/spark/scheduler/SparkListenerInterface")));
    // stack [utils, class]
    insnList.add(new TypeInsnNode(Opcodes.NEW, "scala/collection/immutable/$colon$colon"));
    // stack [utils, class, seq]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [utils, class, seq, seq]
    insnList.add(new LdcInsnNode("com.datadog.spark.DDSparkListener"));
    // stack [utils, class, seq, seq, String]
    insnList.add(
        new FieldInsnNode(
            Opcodes.GETSTATIC,
            "scala/collection/immutable/Nil$",
            "MODULE$",
            "Lscala/collection/immutable/Nil$;"));
    // stack [utils, class, seq, seq, String, List]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "scala/collection/immutable/$colon$colon",
            "<init>",
            "(Ljava/lang/Object;Lscala/collection/immutable/List;)V"));
    // stack [utils, class, seq]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
    // stack [utils, class, seq, this]
    insnList.add(
        new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/apache/spark/SparkContext",
            "_conf",
            "Lorg/apache/spark/SparkConf;"));
    // stack [utils, class, seq, SparkConf]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "org/apache/spark/util/Utils$",
            "loadExtensions",
            "(Ljava/lang/Class;Lscala/collection/Seq;Lorg/apache/spark/SparkConf;)Lscala/collection/Seq;"));
    // stack [seq]
    insnList.add(new InsnNode(Opcodes.ICONST_0));
    // stack [seq, int]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "scala/collection/immutable/Seq",
            "apply",
            "(I)Ljava/lang/Object;",
            true));
    // stack [object]
    insnList.add(
        new TypeInsnNode(Opcodes.CHECKCAST, "org/apache/spark/scheduler/SparkListenerInterface"));
    // stack [sparklistener]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
    // stack [sparklistener, this]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "org/apache/spark/SparkContext",
            "listenerBus",
            "()Lorg/apache/spark/scheduler/LiveListenerBus;"));
    // stack [sparklistener, livelistenerbus]
    insnList.add(new InsnNode(Opcodes.SWAP));
    // stack [livelistenerbus, sparklistener]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "org/apache/spark/scheduler/LiveListenerBus",
            "addToSharedQueue",
            "(Lorg/apache/spark/scheduler/SparkListenerInterface;)V"));
    // stack []
    methodNode.instructions.insert(insnList);
  }

  static class SafeClassWriter extends ClassWriter {
    private final ClassLoader classLoader;

    public SafeClassWriter(ClassLoader classLoader) {
      super(ClassWriter.COMPUTE_FRAMES);
      this.classLoader = classLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
      // We cannot use ASM's getCommonSuperClass because it tries to load super class with
      // ClassLoader which in some circumstances can lead to
      // java.lang.LinkageError: loader (instance of  sun/misc/Launcher$AppClassLoader): attempted
      // duplicate class definition for name: "okhttp3/RealCall"
      // for more info see:
      // https://stackoverflow.com/questions/69563714/linkageerror-attempted-duplicate-class-definition-when-dynamically-instrument
      TypePool tpTargetClassLoader =
          new TypePool.Default.WithLazyResolution(
              TypePool.CacheProvider.Simple.withObjectType(),
              AgentStrategies.locationStrategy().classFileLocator(classLoader, null),
              TypePool.Default.ReaderMode.FAST);
      // Introduced the java agent DataDog classloader for resolving types introduced by other
      // Datadog instrumentation (Tracing, AppSec, Profiling, ...)
      // Here we assume that the current class is loaded in DataDog classloader
      TypePool tpDatadogClassLoader =
          new TypePool.Default.WithLazyResolution(
              TypePool.CacheProvider.Simple.withObjectType(),
              AgentStrategies.locationStrategy()
                  .classFileLocator(getClass().getClassLoader(), null),
              TypePool.Default.ReaderMode.FAST,
              tpTargetClassLoader);

      try {
        TypeDescription td1 = tpDatadogClassLoader.describe(type1.replace('/', '.')).resolve();
        TypeDescription td2 = tpDatadogClassLoader.describe(type2.replace('/', '.')).resolve();
        TypeDescription common = null;
        if (td1.isAssignableFrom(td2)) {
          common = td1;
        } else if (td2.isAssignableFrom(td1)) {
          common = td2;
        } else {
          if (td1.isInterface() || td2.isInterface()) {
            common = tpDatadogClassLoader.describe("java.lang.Object").resolve();
          } else {
            common = td1;
            do {
              common = common.getSuperClass().asErasure();
            } while (!common.isAssignableFrom(td2));
          }
        }
        return common.getInternalName();
      } catch (Exception ex) {
        // ex.printStackTrace();
        return tpDatadogClassLoader.describe("java.lang.Object").resolve().getInternalName();
      }
    }
  }
}
