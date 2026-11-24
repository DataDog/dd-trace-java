package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inserts line probes into the classes selected by the coverage filter. The probes report to {@link
 * ProbeRecorder}; nothing is added to the class itself, so its shape, reflection results and
 * serialization are unchanged.
 */
public final class ProbeClassTransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(ProbeClassTransformer.class);

  private static final ClassLoader AGENT_CLASSLOADER = ProbeClassTransformer.class.getClassLoader();

  private final Predicate<String> instrumentationFilter;

  public ProbeClassTransformer(Predicate<String> instrumentationFilter) {
    this.instrumentationFilter = instrumentationFilter;
  }

  @Override
  @Nullable
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] source) {
    if (loader == null
        || loader == AGENT_CLASSLOADER
        || className == null
        || classBeingRedefined != null
        || className.contains("$$Lambda")
        || !instrumentationFilter.test(className)) {
      return null;
    }
    int majorVersion = ((source[6] & 0xFF) << 8) | (source[7] & 0xFF);
    if (majorVersion < Opcodes.V1_5) {
      return null;
    }
    try {
      return instrument(source, loader, majorVersion >= Opcodes.V1_6);
    } catch (Throwable e) {
      log.debug("Could not insert line probes into {}", className, e);
      return null;
    }
  }

  /**
   * @param frames whether the class file carries stack map frames
   * @return the instrumented class file, or {@code null} if the class has no lines to cover
   */
  @Nullable
  static byte[] instrument(byte[] source, ClassLoader loader, boolean frames) {
    ClassReader reader = new ClassReader(source);
    if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0) {
      return null;
    }
    int classId = ProbeRecorder.reserveClassId();
    if (classId < 0) {
      return null;
    }
    // methods that outgrow the size limit lose their exception probes, then all their probes
    Map<String, Integer> reductions = new HashMap<>();
    while (true) {
      try {
        return instrument(reader, loader, frames, classId, reductions);
      } catch (MethodTooLargeException e) {
        String method = e.getMethodName() + e.getDescriptor();
        if (reductions.merge(method, 1, Integer::sum) > SKIP_METHOD) {
          throw e;
        }
      }
    }
  }

  private static final int NO_EXCEPTION_PROBES = 1;
  private static final int SKIP_METHOD = 2;

  @Nullable
  private static byte[] instrument(
      ClassReader reader,
      ClassLoader loader,
      boolean frames,
      int classId,
      Map<String, Integer> reductions) {
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.EXPAND_FRAMES);
    if (classNode.sourceFile == null) {
      return null; // lines are reported per source file
    }

    ClassProbesBuilder probes = new ClassProbesBuilder();
    MethodNode initializer = null;
    boolean instrumented = false;
    for (MethodNode method : classNode.methods) {
      if ("<clinit>".equals(method.name)) {
        initializer = method;
      } else {
        instrumented |= instrument(classNode, method, classId, probes, false, frames, reductions);
      }
    }
    // initializer probes take the highest ids, so that windows can skip them
    probes.startInitializerProbes();
    if (initializer != null) {
      instrumented |= instrument(classNode, initializer, classId, probes, true, frames, reductions);
    }
    if (!instrumented) {
      return null;
    }

    ClassWriter writer = new ClassWriter(reader, 0);
    classNode.accept(writer);
    byte[] result = writer.toByteArray();
    ProbeRecorder.register(classId, probes.build(classNode.name, loader, classNode.sourceFile));
    return result;
  }

  private static boolean instrument(
      ClassNode classNode,
      MethodNode method,
      int classId,
      ClassProbesBuilder probes,
      boolean initializer,
      boolean frames,
      Map<String, Integer> reductions) {
    int reduction = reductions.getOrDefault(method.name + method.desc, 0);
    if (reduction >= SKIP_METHOD) {
      return false;
    }
    return new MethodProbeInserter(
            classNode.name,
            method,
            classId,
            probes,
            initializer,
            frames,
            reduction < NO_EXCEPTION_PROBES)
        .instrument();
  }
}
