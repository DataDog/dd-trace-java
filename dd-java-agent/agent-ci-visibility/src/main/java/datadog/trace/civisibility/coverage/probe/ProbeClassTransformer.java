package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SourceMap;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /**
   * Patches each class found in the class cache again, compares the two and uses the class patched
   * again. This does the work that the cache saves, so it is for checking the cache only.
   */
  private static final boolean VERIFY_CACHE =
      Boolean.getBoolean("dd.civisibility.experiment.class.cache.verify");

  private static final AtomicBoolean CACHE_MISMATCH_LOGGED = new AtomicBoolean();

  static final String CLASS_LIMIT =
      "the process has more than " + ProbeRecorder.MAX_CLASSES + " classes with probes";
  static final String TOO_LARGE = "the method would outgrow the size limit with probes";
  static final String TOO_LARGE_FOR_EXCEPTION_PROBES =
      "the method would outgrow the size limit with exception probes";
  static final String NO_PROBE_PLACE = "the method has no place for a probe that the JIT accepts";

  private final Predicate<String> instrumentationFilter;
  @Nullable private final ProbeClassCache cache;

  public ProbeClassTransformer(Predicate<String> instrumentationFilter) {
    this(instrumentationFilter, null);
  }

  /**
   * @param cache the patched classes that the test JVMs of the build share, or {@code null} to
   *     patch every class in this JVM
   */
  public ProbeClassTransformer(
      Predicate<String> instrumentationFilter, @Nullable ProbeClassCache cache) {
    this.instrumentationFilter = instrumentationFilter;
    this.cache = cache;
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
      ProbeLosses.classLost(className, "the class file is older than Java 5", null);
      return null;
    }
    try {
      return instrument(source, loader, majorVersion >= Opcodes.V1_6, cache);
    } catch (Throwable e) {
      ProbeLosses.classLost(className, "inserting probes failed with " + e, e);
      return null;
    }
  }

  @Nullable
  static byte[] instrument(byte[] source, ClassLoader loader, boolean frames) {
    return instrument(source, loader, frames, null);
  }

  /**
   * Instruments a class and registers its probes.
   *
   * @param frames whether the class file carries stack map frames
   * @param cache the class cache, or {@code null}
   * @return the instrumented class file, or {@code null} if the class has no lines to cover
   */
  @Nullable
  static byte[] instrument(
      byte[] source, ClassLoader loader, boolean frames, @Nullable ProbeClassCache cache) {
    Instrumented instrumented = instrument(source, frames, cache);
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
    return instrument(source, frames, null);
  }

  @Nullable
  private static Instrumented instrument(
      byte[] source, boolean frames, @Nullable ProbeClassCache cache) {
    if (!frames || ASM_ONLY) {
      PatcherStats.count(frames ? "asm: switch" : "asm: class file before Java 6");
      return instrumentWithAsm(source, frames, -1);
    }
    File entryFile = null;
    ProbeClassCache.Entry cached = null;
    if (cache != null) {
      entryFile = cache.entryFile(source);
      cached = cache.read(entryFile, source);
      PatcherStats.count(cached != null ? "cache: hit" : "cache: miss");
      if (cached != null && !VERIFY_CACHE) {
        return fromCache(cached);
      }
    }
    ClassFile classFile;
    try {
      classFile = new ClassFile(source);
    } catch (RuntimeException e) {
      return instrumentWithAsm(source, -1, "class file not read", cached);
    }
    if ((classFile.access & Opcodes.ACC_MODULE) != 0) {
      return null;
    }
    String className = classFile.className(classFile.thisClass);
    int classId = ProbeRecorder.reserveClassId();
    if (classId < 0) {
      ProbeLosses.classLost(className, CLASS_LIMIT, null);
      return null;
    }
    List<String> skippedMethods = new ArrayList<>();
    Instrumented instrumented;
    try {
      instrumented = ClassFilePatcher.instrument(classFile, classId, cache != null, skippedMethods);
    } catch (UnsupportedClassException e) {
      return instrumentWithAsm(source, classId, e.reason, cached);
    } catch (RuntimeException e) {
      log.debug("Class file patcher failed on {}", className, e);
      return instrumentWithAsm(
          source, classId, "patcher failed with " + e.getClass().getName(), cached);
    }
    if (cached != null) {
      String difference = cached.difference(classId, className, skippedMethods, instrumented);
      if (difference == null) {
        PatcherStats.count("cache: verified");
      } else {
        cacheMismatch(className, difference);
      }
    } else if (cache != null) {
      cache.write(entryFile, source, className, skippedMethods, instrumented);
    }
    return reportOutcome(className, skippedMethods, instrumented);
  }

  /** A class from the class cache, with the class id it gets in this JVM. */
  @Nullable
  private static Instrumented fromCache(ProbeClassCache.Entry cached) {
    Instrumented instrumented = null;
    if (cached.hasLines()) {
      int classId = ProbeRecorder.reserveClassId();
      if (classId < 0) {
        ProbeLosses.classLost(cached.className, CLASS_LIMIT, null);
        return null;
      }
      instrumented = cached.instrumented(classId);
    }
    return reportOutcome(cached.className, Arrays.asList(cached.skippedMethods), instrumented);
  }

  /** Reports the methods the class file patcher left without probes, and counts the outcome. */
  @Nullable
  private static Instrumented reportOutcome(
      String className, List<String> skippedMethods, @Nullable Instrumented instrumented) {
    for (String method : skippedMethods) {
      ProbeLosses.methodLost(className, method, NO_PROBE_PLACE);
    }
    PatcherStats.count(instrumented != null ? "patched" : "no lines");
    return instrumented;
  }

  /**
   * Instruments with ASM a class that the class file patcher left to it.
   *
   * @param cached the cache entry of the class, when it is checked, which then differs
   */
  @Nullable
  private static Instrumented instrumentWithAsm(
      byte[] source, int classId, String reason, @Nullable ProbeClassCache.Entry cached) {
    PatcherStats.count("asm: " + reason);
    if (cached != null) {
      cacheMismatch(cached.className, "the class file patcher now leaves it to ASM");
    }
    return instrumentWithAsm(source, true, classId);
  }

  private static void cacheMismatch(String className, String difference) {
    PatcherStats.count("cache: verify mismatch");
    if (CACHE_MISMATCH_LOGGED.compareAndSet(false, true)) {
      log.warn(
          "A class read from the line probe class cache differs from the class patched again: {}"
              + " ({}). The class patched again is used.",
          className,
          difference);
    }
  }

  /** A class with probes inserted, and what is needed to register them. */
  static final class Instrumented {
    final int classId;
    final byte[] bytes;
    final String className;
    final String sourceFile;
    @Nullable final String sourceDebug;
    final ClassProbesBuilder probes;

    /**
     * Where the value of the constant that the class id is loaded from starts in {@link #bytes}, or
     * -1 if the class id is pushed as a value and the class cannot be cached.
     */
    final int classIdSlot;

    Instrumented(
        int classId,
        byte[] bytes,
        String className,
        String sourceFile,
        @Nullable String sourceDebug,
        ClassProbesBuilder probes,
        int classIdSlot) {
      this.classId = classId;
      this.bytes = bytes;
      this.className = className;
      this.sourceFile = sourceFile;
      this.sourceDebug = sourceDebug;
      this.probes = probes;
      this.classIdSlot = classIdSlot;
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
        ProbeLosses.classLost(reader.getClassName(), CLASS_LIMIT, null);
        return null;
      }
    }
    // methods that outgrow the size limit lose their exception probes, then all their probes;
    // methods with no place for a probe that the JIT compilers accept lose all their probes
    Map<String, Integer> reductions = new HashMap<>();
    Set<String> withoutProbePlace = new HashSet<>();
    while (true) {
      try {
        Instrumented instrumented = instrument(reader, frames, classId, reductions);
        countReductions(reader.getClassName(), reductions, withoutProbePlace);
        return instrumented;
      } catch (MethodTooLargeException e) {
        String method = e.getMethodName() + e.getDescriptor();
        if (reductions.merge(method, 1, Integer::sum) > SKIP_METHOD) {
          throw e;
        }
      } catch (MethodProbeInserter.ProbePlacementException e) {
        String method = e.methodName + e.descriptor;
        Integer previous = reductions.put(method, SKIP_METHOD);
        if (previous != null && previous == SKIP_METHOD) {
          throw e;
        }
        withoutProbePlace.add(method);
      }
    }
  }

  private static void countReductions(
      String className, Map<String, Integer> reductions, Set<String> withoutProbePlace) {
    for (Map.Entry<String, Integer> reduction : reductions.entrySet()) {
      String method = reduction.getKey();
      if (reduction.getValue() >= SKIP_METHOD) {
        ProbeLosses.methodLost(
            className, method, withoutProbePlace.contains(method) ? NO_PROBE_PLACE : TOO_LARGE);
      } else {
        ProbeLosses.exceptionProbesLost(className, method, TOO_LARGE_FOR_EXCEPTION_PROBES);
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
        probes,
        -1);
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
