package datadog.trace.codecoverage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.InjectedClassRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ClassFileTransformer} that uses JaCoCo's {@link Instrumenter} to insert boolean probes
 * into class bytecode at load time.
 *
 * <p>Must be registered <b>before</b> ByteBuddy's transformer so that JaCoCo sees original class
 * bytes (CRC64 must match the {@code .class} files on disk for analysis to work).
 */
public final class CodeCoverageTransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(CodeCoverageTransformer.class);

  private final RuntimeData runtimeData;
  private final Instrumenter instrumenter;
  private final Predicate<String> filter;
  private final ConcurrentLinkedQueue<String> newlyInstrumented = new ConcurrentLinkedQueue<>();

  /**
   * Initializes the JaCoCo runtime and instrumenter.
   *
   * <p>This replicates the logic from JaCoCo's {@code AgentModule} and {@code PreMain}: it creates
   * an isolated classloader, opens {@code java.lang} to it via {@code
   * Instrumentation.redefineModule}, loads {@link InjectedClassRuntime} in that module, and starts
   * the runtime.
   *
   * @param inst the JVM instrumentation service
   * @param filter predicate that decides which classes to instrument (VM class name format)
   * @throws Exception if the JaCoCo runtime cannot be initialized
   */
  public CodeCoverageTransformer(Instrumentation inst, Predicate<String> filter) throws Exception {
    this.filter = filter;
    this.runtimeData = new RuntimeData();

    // Replicate AgentModule logic: create isolated classloader and open java.lang to it
    Set<String> scope = new HashSet<>();
    addToScopeWithInnerClasses(InjectedClassRuntime.class, scope);

    // Use the classloader that has the (shaded) JaCoCo classes as the resource source and parent.
    // The parent provides access to AbstractRuntime, IRuntime, RuntimeData, ASM classes, etc.
    // Scoped classes (InjectedClassRuntime and its inner classes) are re-defined in the isolated
    // classloader so they belong to its distinct unnamed module — which has java.lang opened to it.
    ClassLoader agentLoader = CodeCoverageTransformer.class.getClassLoader();

    ClassLoader isolatedLoader =
        new ClassLoader(agentLoader) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!scope.contains(name)) {
              return super.loadClass(name, resolve);
            }
            byte[] bytes;
            try (InputStream resourceStream =
                agentLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
              if (resourceStream == null) {
                throw new ClassNotFoundException(name);
              }
              bytes = readAllBytes(resourceStream);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            return defineClass(
                name, bytes, 0, bytes.length, CodeCoverageTransformer.class.getProtectionDomain());
          }
        };

    // Open java.lang package to the isolated classloader's unnamed module
    openPackage(inst, Object.class, isolatedLoader);

    // Load InjectedClassRuntime in the isolated module
    @SuppressWarnings("unchecked")
    Class<InjectedClassRuntime> rtClass =
        (Class<InjectedClassRuntime>)
            isolatedLoader.loadClass(InjectedClassRuntime.class.getName());

    IRuntime runtime =
        rtClass.getConstructor(Class.class, String.class).newInstance(Object.class, "$DDCov");

    runtime.startup(runtimeData);
    this.instrumenter = new Instrumenter(runtime);
  }

  /** Recursively adds the given class and all its declared inner classes to the scope set. */
  private static void addToScopeWithInnerClasses(Class<?> clazz, Set<String> scope) {
    scope.add(clazz.getName());
    for (Class<?> inner : clazz.getDeclaredClasses()) {
      addToScopeWithInnerClasses(inner, scope);
    }
  }

  /** Reads all bytes from an input stream. */
  private static byte[] readAllBytes(InputStream is) throws IOException {
    byte[] buf = new byte[1024];
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int r;
    while ((r = is.read(buf)) != -1) {
      out.write(buf, 0, r);
    }
    return out.toByteArray();
  }

  /**
   * Opens the package of {@code classInPackage} to the unnamed module of {@code targetLoader}.
   *
   * <p>This uses {@code Instrumentation.redefineModule} reflectively (same approach as JaCoCo's
   * {@code AgentModule.openPackage}).
   */
  private static void openPackage(
      Instrumentation inst, Class<?> classInPackage, ClassLoader targetLoader) throws Exception {
    // module of the package to open (e.g. java.base for java.lang)
    Object module = Class.class.getMethod("getModule").invoke(classInPackage);

    // unnamed module of the isolated classloader
    Object unnamedModule = ClassLoader.class.getMethod("getUnnamedModule").invoke(targetLoader);

    Class<?> moduleClass = Class.forName("java.lang.Module");

    // Instrumentation.redefineModule(Module, Set, Map, Map<String, Set<Module>>, Set, Map)
    Instrumentation.class
        .getMethod(
            "redefineModule", moduleClass, Set.class, Map.class, Map.class, Set.class, Map.class)
        .invoke(
            inst,
            module, // module to modify
            Collections.emptySet(), // extraReads
            Collections.emptyMap(), // extraExports
            Collections.singletonMap(
                classInPackage.getPackage().getName(),
                Collections.singleton(unnamedModule)), // extraOpens
            Collections.emptySet(), // extraUses
            Collections.emptyMap()); // extraProvides
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain pd,
      byte[] classfileBuffer) {
    if (classBeingRedefined != null) {
      return null; // retransformation not supported (schema change)
    }
    if (className == null || loader == null) {
      return null; // skip bootstrap classes and unnamed classes
    }
    if (!filter.test(className)) {
      return null;
    }
    try {
      byte[] instrumented = instrumenter.instrument(classfileBuffer, className);
      newlyInstrumented.add(className);
      return instrumented;
    } catch (Exception e) {
      log.debug("Failed to instrument class {}", className, e);
      return null;
    }
  }

  /**
   * Drains and returns the list of class names that have been instrumented since the last call.
   * Each class name is in VM internal format (e.g. {@code com/example/MyClass}).
   */
  public List<String> drainNewClasses() {
    List<String> result = new ArrayList<>();
    String name;
    while ((name = newlyInstrumented.poll()) != null) {
      result.add(name);
    }
    return result;
  }

  /**
   * Collects current probe data and resets all probes to {@code false}.
   *
   * <p>Uses a serialize/deserialize round-trip to capture probe values before reset. This is
   * necessary because {@code RuntimeData.collect()} passes references to the live {@code boolean[]}
   * probe arrays to the visitor. If we passed an {@code ExecutionDataStore} directly, it would
   * store references to the same arrays that {@code reset()} then zeroes out — destroying the
   * collected data. The byte-stream approach (same as JaCoCo's own {@code
   * Agent.getExecutionData()}) captures probe values into the stream before the reset runs.
   *
   * @param target store to receive the execution data
   * @param sessionTarget store to receive session info
   */
  public void collectAndReset(ExecutionDataStore target, SessionInfoStore sessionTarget) {
    try {
      // Serialize probe data to bytes (captures values before reset)
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      ExecutionDataWriter writer = new ExecutionDataWriter(buffer);
      runtimeData.collect(writer, writer, true);

      // Deserialize into the target stores
      ExecutionDataReader reader =
          new ExecutionDataReader(new java.io.ByteArrayInputStream(buffer.toByteArray()));
      reader.setExecutionDataVisitor(target);
      reader.setSessionInfoVisitor(sessionTarget);
      reader.read();
    } catch (IOException e) {
      throw new RuntimeException("Failed to collect coverage data", e);
    }
  }
}
