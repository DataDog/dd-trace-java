package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SourceMap;
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
 *
 * <p>Class files with stack map frames are patched in place by {@link ClassFilePatcher}, which
 * leaves the classes it does not handle to the ASM transformer here. Both insert the same probes.
 */
public final class ProbeClassTransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(ProbeClassTransformer.class);

  private static final ClassLoader AGENT_CLASSLOADER = ProbeClassTransformer.class.getClassLoader();

  /**
   * The package of the recorder that probes call. Its classes never get probes, whatever loader
   * defines them and whatever the filter accepts: a probe in them would call the recorder again.
   */
  private static final String RECORDER_PACKAGE = "datadog/trace/api/civisibility/coverage/";

  /** Sends every class through ASM, to compare it with the class file patcher. */
  private static final boolean ASM_ONLY =
      Boolean.getBoolean("dd.civisibility.experiment.asm.transformer");

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
        || className.startsWith(RECORDER_PACKAGE)
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
   * Instruments a class and registers its probes.
   *
   * @param frames whether the class file carries stack map frames
   * @return the instrumented class file, or {@code null} if the class has no lines to cover
   */
  @Nullable
  static byte[] instrument(byte[] source, ClassLoader loader, boolean frames) {
    Instrumented instrumented = instrument(source, frames);
    if (instrumented == null) {
      return null;
    }
    SourceMap sourceMap =
        SmapParser.parse(
            instrumented.sourceDebug, SmapParser.packageDirectory(instrumented.className));
    ProbeRecorder.register(
        instrumented.classId,
        instrumented.probes.build(
            instrumented.className, loader, instrumented.sourceFile, sourceMap));
    return instrumented.bytes;
  }

  /**
   * Instruments a class without registering its probes.
   *
   * @return the instrumented class, or {@code null} if the class has no lines to cover
   */
  @Nullable
  static Instrumented instrument(byte[] source, boolean frames) {
    if (!frames || ASM_ONLY) {
      PatcherStats.count(frames ? "asm: switch" : "asm: class file before Java 6");
      return instrumentWithAsm(source, frames, -1);
    }
    ClassFile classFile;
    try {
      classFile = new ClassFile(source);
    } catch (RuntimeException e) {
      PatcherStats.count("asm: class file not read");
      return instrumentWithAsm(source, true, -1);
    }
    if ((classFile.access & Opcodes.ACC_MODULE) != 0) {
      return null;
    }
    int classId = ProbeRecorder.reserveClassId();
    if (classId < 0) {
      return null;
    }
    try {
      Instrumented instrumented = ClassFilePatcher.instrument(classFile, classId);
      PatcherStats.count(instrumented != null ? "patched" : "no lines");
      return instrumented;
    } catch (UnsupportedClassException e) {
      PatcherStats.count("asm: " + e.reason);
    } catch (RuntimeException e) {
      PatcherStats.count("asm: patcher failed with " + e.getClass().getName());
      log.debug("Class file patcher failed on {}", classFile.className(classFile.thisClass), e);
    }
    return instrumentWithAsm(source, true, classId);
  }

  /** A class with probes inserted, and what is needed to register them. */
  static final class Instrumented {
    final int classId;
    final byte[] bytes;
    final String className;
    final String sourceFile;
    @Nullable final String sourceDebug;
    final ClassProbesBuilder probes;

    Instrumented(
        int classId,
        byte[] bytes,
        String className,
        String sourceFile,
        @Nullable String sourceDebug,
        ClassProbesBuilder probes) {
      this.classId = classId;
      this.bytes = bytes;
      this.className = className;
      this.sourceFile = sourceFile;
      this.sourceDebug = sourceDebug;
      this.probes = probes;
    }
  }

  /**
   * Instruments a class with ASM.
   *
   * @param classId the id reserved for the class, or -1 to reserve one
   * @return the instrumented class, or {@code null} if the class has no lines to cover
   */
  @Nullable
  static Instrumented instrumentWithAsm(byte[] source, boolean frames, int classId) {
    ClassReader reader = new ClassReader(source);
    if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0) {
      return null;
    }
    if (classId < 0) {
      classId = ProbeRecorder.reserveClassId();
      if (classId < 0) {
        return null;
      }
    }
    // methods that outgrow the size limit lose their exception probes, then all their probes;
    // methods with no place for a probe that the JIT compilers accept lose all their probes
    Map<String, Integer> reductions = new HashMap<>();
    while (true) {
      try {
        return instrument(reader, frames, classId, reductions);
      } catch (MethodTooLargeException e) {
        String method = e.getMethodName() + e.getDescriptor();
        if (reductions.merge(method, 1, Integer::sum) > SKIP_METHOD) {
          throw e;
        }
      } catch (MethodProbeInserter.ProbePlacementException e) {
        Integer previous = reductions.put(e.methodName + e.descriptor, SKIP_METHOD);
        if (previous != null && previous == SKIP_METHOD) {
          throw e;
        }
      }
    }
  }

  private static final int NO_EXCEPTION_PROBES = 1;
  private static final int SKIP_METHOD = 2;

  @Nullable
  private static Instrumented instrument(
      ClassReader reader, boolean frames, int classId, Map<String, Integer> reductions) {
    ClassNode classNode = new ClassNode();
    // frames stay compressed; the inserter expands those of the methods it changes
    reader.accept(classNode, 0);
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
    return new Instrumented(
        classId,
        writer.toByteArray(),
        classNode.name,
        classNode.sourceFile,
        classNode.sourceDebug,
        probes);
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
