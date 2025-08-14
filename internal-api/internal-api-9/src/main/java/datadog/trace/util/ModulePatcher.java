package datadog.trace.util;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for patching JPMS (Java Platform Module System) modules to provide additional access
 * permissions required by internal Datadog Java Agent services.
 *
 * <p>The ModulePatcher framework allows internal agent components (not instrumentations) to
 * declaratively specify what module permissions they need (opens, exports, reads, uses, provides)
 * and automatically applies them using {@link Instrumentation#redefineModule}.
 *
 * <p><strong>Note:</strong> This is for internal agent services only. Regular instrumentations
 * should use {@code HelperInjector} to ensure proper module setup and should not use this framework
 * directly.
 *
 * <h3>Usage</h3>
 *
 * For internal agent services that require JDK module access:
 *
 * <ol>
 *   <li>Implement {@link ModulePatcher.Impl}
 *   <li>Create {@code META-INF/services/datadog.trace.util.ModulePatcher$Impl} file
 *   <li>Add your implementation class name to the service file
 * </ol>
 *
 * <h3>Example</h3>
 *
 * <pre>{@code
 * // For internal agent services accessing JDK internals
 * public class ProcessContextModulePatcher implements ModulePatcher.Impl {
 *   @Override
 *   public ModulePatch patchModule() {
 *     return new ModulePatch(ProcessContext.class, "java.base")
 *         .addOpen("java.nio", SELF_MODULE_NAME);
 *   }
 * }
 * }</pre>
 *
 * @since 1.0
 */
public final class ModulePatcher {
  private static final Logger log = LoggerFactory.getLogger(ModulePatcher.class);
  private static final ModuleLayer BOOT_LAYER = ModuleLayer.boot();

  /**
   * Special module name constant representing the module of the requesting class. Use this to refer
   * to the module containing the class that needs access permissions.
   */
  public static final String SELF_MODULE_NAME = "self";

  /**
   * Represents a collection of module permissions to be applied to a specific module.
   *
   * <p>This class provides a fluent API for building module patches that define what packages
   * should be opened, exported, what modules should be read, what services should be used, and what
   * service implementations should be provided.
   *
   * <p>The patch is applied to the target module specified in the constructor, and permissions are
   * granted relative to the origin class's module context.
   */
  public static final class ModulePatch {
    final Class<?> origin;
    private final String module;
    Map<String, Set<String>> extraOpen = new HashMap<>();
    Set<String> extraRead = new HashSet<>();
    Set<String> extraUse = new HashSet<>();
    Map<String, Set<String>> extraExports = new HashMap<>();
    Map<String, Set<String>> extraProvides = new HashMap<>();

    /**
     * Creates a new module patch for the specified target module.
     *
     * @param origin the class requesting the module permissions (used for context)
     * @param module the name of the module to be patched (e.g., "java.base")
     */
    public ModulePatch(Class<?> origin, String module) {
      this.origin = origin;
      this.module = module;
    }

    /**
     * Opens a package to a specific module, allowing deep reflection access.
     *
     * <p>Opening a package allows the target module to perform deep reflection operations like
     * {@code setAccessible(true)} on non-public members.
     *
     * @param pkg the package name to open (e.g., "java.nio")
     * @param module the target module name, or {@link #SELF_MODULE_NAME} for the origin module
     * @return this ModulePatch for method chaining
     */
    public ModulePatch addOpen(String pkg, String module) {
      return addOpen(pkg, Collections.singleton(module));
    }

    /**
     * Opens a package to multiple modules, allowing deep reflection access.
     *
     * @param pkg the package name to open (e.g., "java.nio")
     * @param modules the set of target module names
     * @return this ModulePatch for method chaining
     * @see #addOpen(String, String)
     */
    public ModulePatch addOpen(String pkg, Set<String> modules) {
      extraOpen.computeIfAbsent(pkg, k -> new HashSet<>()).addAll(modules);
      return this;
    }

    /**
     * Adds a module read edge, allowing this module to read the specified module.
     *
     * <p>Reading a module makes its exported packages available for import and use.
     *
     * @param read the name of the module to read
     * @return this ModulePatch for method chaining
     */
    public ModulePatch addRead(String read) {
      extraRead.add(read);
      return this;
    }

    /**
     * Adds multiple module read edges.
     *
     * @param read the first module to read
     * @param other additional modules to read
     * @return this ModulePatch for method chaining
     * @see #addRead(String)
     */
    public ModulePatch addRead(String read, String... other) {
      extraRead.add(read);
      extraRead.addAll(Arrays.asList(other));
      return this;
    }

    /**
     * Declares that this module uses a service interface.
     *
     * @param useClz the fully qualified service interface class name
     * @return this ModulePatch for method chaining
     */
    public ModulePatch addUse(String useClz) {
      extraUse.add(useClz);
      return this;
    }

    /**
     * Declares that this module uses multiple service interfaces.
     *
     * @param useClz the first service interface class name
     * @param other additional service interface class names
     * @return this ModulePatch for method chaining
     * @see #addUse(String)
     */
    public ModulePatch addUse(String useClz, String... other) {
      extraUse.add(useClz);
      extraUse.addAll(Arrays.asList(other));
      return this;
    }

    /**
     * Exports a package to a specific module, making it available for import.
     *
     * <p>Exporting makes public types in the package visible to the target module but does not
     * allow deep reflection (use {@link #addOpen} for that).
     *
     * @param export the package name to export (e.g., "java.lang")
     * @param module the target module name, or {@link #SELF_MODULE_NAME} for the origin module
     * @return this ModulePatch for method chaining
     */
    public ModulePatch addExport(String export, String module) {
      return addExport(export, Collections.singleton(module));
    }

    /**
     * Exports a package to multiple modules.
     *
     * @param pkg the package name to export
     * @param modules the set of target module names
     * @return this ModulePatch for method chaining
     * @see #addExport(String, String)
     */
    public ModulePatch addExport(String pkg, Set<String> modules) {
      extraExports.computeIfAbsent(pkg, k -> new HashSet<>()).addAll(modules);
      return this;
    }

    /**
     * Declares that this module provides implementations for a service interface.
     *
     * @param clz the service interface class name
     * @param clzs the set of implementation class names
     * @return this ModulePatch for method chaining
     */
    public ModulePatch addProvides(String clz, Set<String> clzs) {
      extraProvides.computeIfAbsent(clz, k -> new HashSet<>()).addAll(clzs);
      return this;
    }

    /**
     * Checks if this patch defines any module permissions.
     *
     * @return true if any opens, exports, reads, uses, or provides are defined
     */
    public boolean hasPatches() {
      return !extraOpen.isEmpty()
          || !extraRead.isEmpty()
          || !extraUse.isEmpty()
          || !extraExports.isEmpty()
          || !extraProvides.isEmpty();
    }

    /**
     * Merges another module patch into this one, combining all permissions.
     *
     * @param other the module patch to merge into this one
     */
    void subsume(ModulePatch other) {
      extraOpen.putAll(other.extraOpen);
      extraRead.addAll(other.extraRead);
      extraUse.addAll(other.extraUse);
      extraExports.putAll(other.extraExports);
      extraProvides.putAll(other.extraProvides);
    }

    @Override
    public String toString() {
      return "ModulePatch{"
          + "module='"
          + module
          + '\''
          + ", extraOpen="
          + extraOpen
          + ", extraRead="
          + extraRead
          + ", extraUse="
          + extraUse
          + ", extraExports="
          + extraExports
          + ", extraProvides="
          + extraProvides
          + '}';
    }
  }

  /**
   * Interface to be implemented by custom module patchers.
   *
   * <p>Implementations should be registered via the Java ServiceLoader mechanism by creating a
   * {@code META-INF/services/datadog.trace.util.ModulePatcher$Impl} file containing the fully
   * qualified class name of the implementation.
   *
   * <p><strong>Important:</strong> Note the {@code $} character in the service file name, which is
   * required for inner class registration with ServiceLoader.
   */
  public interface Impl {
    /**
     * Defines the module permissions required by this patcher.
     *
     * @return a ModulePatch specifying the required permissions, or null if no patching is needed
     */
    ModulePatch patchModule();
  }

  /**
   * Applies all registered module patches using the provided instrumentation.
   *
   * <p>This method discovers all {@link Impl} instances via ServiceLoader from both the system
   * classloader and the provided classloader, then applies their patches using {@link
   * Instrumentation#redefineModule(Module, Set, Map, Map, Set, Map)}.
   *
   * <p>Multiple patches for the same module are automatically merged together.
   *
   * @param instrumentation the instrumentation instance for applying module patches
   * @param classLoader additional classloader to search for module patchers (may be null)
   */
  // Called via reflection from Agent.java
  public static void patchModules(Instrumentation instrumentation, ClassLoader classLoader) {
    Map<String, ModulePatch> patches = new HashMap<>();

    collectRequests(ModulePatcher.class.getClassLoader(), patches);
    if (classLoader != null) {
      collectRequests(classLoader, patches);
    }

    for (ModulePatch patch : patches.values()) {
      BOOT_LAYER
          .findModule(patch.module)
          .ifPresent(
              m -> {
                Set<Module> extraReads = fromModuleNames(patch.extraRead, patch.origin);
                Set<Class<?>> extraUses =
                    new HashSet<>(fromClassNames(patch.extraUse, patch.origin));
                Map<String, Set<Module>> extraOpens =
                    fromPkgToModuleNames(patch.extraOpen, patch.origin);
                Map<String, Set<Module>> extraExports =
                    fromPkgToModuleNames(patch.extraExports, patch.origin);
                Map<Class<?>, List<Class<?>>> extraProvides =
                    patch.extraProvides.entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                e -> loadClass(e.getKey(), patch.origin),
                                e -> fromClassNames(e.getValue(), patch.origin)));

                instrumentation.redefineModule(
                    m, extraReads, extraExports, extraOpens, extraUses, extraProvides);
              });
    }
  }

  private static void collectRequests(ClassLoader classLoader, Map<String, ModulePatch> patches) {
    for (Impl patcher :
        ServiceLoader.load(
            Impl.class, classLoader != null ? classLoader : ClassLoader.getSystemClassLoader())) {
      log.debug(
          "Looking at custom module patcher: {} in classloader {}",
          patcher.getClass().getName(),
          classLoader);
      ModulePatch patchModule = patcher.patchModule();
      if (patchModule != null && patchModule.hasPatches()) {
        log.debug("Got module patch request: {}", patchModule);
        patches
            .computeIfAbsent(
                patchModule.module, k -> new ModulePatch(patcher.getClass(), patchModule.module))
            .subsume(patchModule);
      }
    }
  }

  private static Map<String, Set<Module>> fromPkgToModuleNames(
      Map<String, Set<String>> names, Class<?> onBehalf) {
    return names.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> fromModuleNames(e.getValue(), onBehalf)));
  }

  private static Set<Module> fromModuleNames(Set<String> moduleNames, Class<?> onBehalf) {
    return moduleNames.stream()
        .map(m -> getModule(m, onBehalf))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toSet());
  }

  private static List<Class<?>> fromClassNames(Collection<String> classNames, Class<?> onBehalf) {
    return classNames.stream().map(n -> loadClass(n, onBehalf)).collect(Collectors.toList());
  }

  private static Optional<Module> getModule(String name, Class<?> onBehalf) {
    if (SELF_MODULE_NAME.equals(name)) {
      return Optional.of(onBehalf.getModule());
    }
    return BOOT_LAYER.findModule(name);
  }

  private static Class<?> loadClass(String className, Class<?> onBehalf) {
    ClassLoader cl =
        onBehalf.getClassLoader() != null
            ? onBehalf.getClassLoader()
            : ClassLoader.getSystemClassLoader();
    try {
      return cl.loadClass(className);
    } catch (ClassNotFoundException e) {
      log.warn("Unable to resolve class {} in classloader {}", className, cl, e);
    }
    return null;
  }
}
