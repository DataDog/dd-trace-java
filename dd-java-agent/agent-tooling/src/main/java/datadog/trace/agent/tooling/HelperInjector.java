package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;
import static datadog.trace.bootstrap.AgentClassLoading.INJECTING_HELPERS;

import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureClassLoader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Injects instrumentation helper classes into the user's classloader. */
public class HelperInjector implements Instrumenter.AdviceTransformer {
  private static final Logger log = LoggerFactory.getLogger(HelperInjector.class);
  // Need this because we can't put null into the injectedClassLoaders map.
  private static final ClassLoader BOOTSTRAP_CLASSLOADER_PLACEHOLDER =
      new SecureClassLoader(null) {
        @Override
        public String toString() {
          return "<bootstrap>";
        }
      };

  static {
    if (Config.get().isTempJarsCleanOnBoot()) {
      cleanTempJars();
    }
  }

  private final String requestingName;

  private final Set<String> helperClassNames;
  private final Map<String, byte[]> dynamicTypeMap = new LinkedHashMap<>();

  private final Map<ClassLoader, Boolean> injectedClassLoaders =
      Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

  private final List<WeakReference<Object>> helperModules = new CopyOnWriteArrayList<>();
  /**
   * Construct HelperInjector.
   *
   * @param helperClassNames binary names of the helper classes to inject. These class names must be
   *     resolvable by the classloader returned by
   *     datadog.trace.agent.tooling.Utils#getAgentClassLoader(). Classes are injected in the order
   *     provided. This is important if there is interdependency between helper classes that
   *     requires them to be injected in a specific order.
   */
  public HelperInjector(final String requestingName, final String... helperClassNames) {
    this.requestingName = requestingName;

    this.helperClassNames = new LinkedHashSet<>(Arrays.asList(helperClassNames));
  }

  public HelperInjector(final String requestingName, final Map<String, byte[]> helperMap) {
    this.requestingName = requestingName;

    helperClassNames = helperMap.keySet();
    dynamicTypeMap.putAll(helperMap);
  }

  public static HelperInjector forDynamicTypes(
      final String requestingName, final Collection<DynamicType.Unloaded<?>> helpers) {
    final Map<String, byte[]> helperMap = new HashMap<>(helpers.size());
    for (final DynamicType.Unloaded<?> helper : helpers) {
      helperMap.put(helper.getTypeDescription().getName(), helper.getBytes());
    }
    return new HelperInjector(requestingName, helperMap);
  }

  private Map<String, byte[]> getHelperMap() throws IOException {
    if (dynamicTypeMap.isEmpty()) {
      final Map<String, byte[]> classnameToBytes = new LinkedHashMap<>();

      final ClassFileLocator locator =
          ClassFileLocator.ForClassLoader.of(Utils.getAgentClassLoader());

      for (final String helperClassName : helperClassNames) {
        final byte[] classBytes = locator.locate(helperClassName).resolve();
        classnameToBytes.put(helperClassName, classBytes);
      }

      return classnameToBytes;
    } else {
      return dynamicTypeMap;
    }
  }

  @Override
  public DynamicType.Builder<?> transform(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      ClassLoader classLoader,
      final JavaModule module) {
    if (!helperClassNames.isEmpty()) {
      if (classLoader == BOOTSTRAP_CLASSLOADER) {
        classLoader = BOOTSTRAP_CLASSLOADER_PLACEHOLDER;
      }

      if (!injectedClassLoaders.containsKey(classLoader)) {
        try {
          if (log.isDebugEnabled()) {
            log.debug(
                "Injecting helper classes - instrumentation.class={} instrumentation.target.classloader={} instrumentation.helper_classes=[{}]",
                requestingName,
                classLoader,
                Strings.join(",", helperClassNames));
          }

          final Map<String, byte[]> classnameToBytes = getHelperMap();
          final Map<String, Class<?>> classes;
          if (classLoader == BOOTSTRAP_CLASSLOADER_PLACEHOLDER) {
            classes = injectBootstrapClassLoader(classnameToBytes);
          } else {
            classes = injectClassLoader(classLoader, classnameToBytes);
          }

          // All datadog helper classes are in the unnamed module
          // And there's exactly one unnamed module per classloader
          // Use the module of the first class for convenience
          if (JavaModule.isSupported()) {
            final JavaModule javaModule = JavaModule.ofType(classes.values().iterator().next());
            helperModules.add(new WeakReference<>(javaModule.unwrap()));
          }
        } catch (final Exception e) {
          if (log.isErrorEnabled()) {
            log.error(
                "Failed to inject helper classes - instrumentation.class={} instrumentation.target.classloader={} instrumentation.target.class={}",
                requestingName,
                classLoader,
                typeDescription,
                e);
          }
          throw new RuntimeException(e);
        }

        injectedClassLoaders.put(classLoader, true);
      }

      ensureModuleCanReadHelperModules(module);
    }
    return builder;
  }

  private Map<String, Class<?>> injectBootstrapClassLoader(
      final Map<String, byte[]> classnameToBytes) throws IOException {
    // Mar 2020: Since we're proactively cleaning up tempDirs, we cannot share dirs per thread.
    // If this proves expensive, we could do a per-process tempDir with
    // a reference count -- but for now, starting simple.

    // Failures to create a tempDir are propagated as IOException and handled by transform
    final File tempDir = createTempDir();
    INJECTING_HELPERS.begin();
    try {
      return ClassInjector.UsingInstrumentation.of(
              tempDir,
              ClassInjector.UsingInstrumentation.Target.BOOTSTRAP,
              Utils.getInstrumentation())
          .injectRaw(classnameToBytes);
    } finally {
      INJECTING_HELPERS.end();
      // Delete fails silently
      deleteTempDir(tempDir);
    }
  }

  private Map<String, Class<?>> injectClassLoader(
      final ClassLoader classLoader, final Map<String, byte[]> classnameToBytes) {
    INJECTING_HELPERS.begin();
    try {
      return new ClassInjector.UsingReflection(classLoader).injectRaw(classnameToBytes);
    } finally {
      INJECTING_HELPERS.end();
    }
  }

  private void ensureModuleCanReadHelperModules(final JavaModule target) {
    if (JavaModule.isSupported() && target != JavaModule.UNSUPPORTED && target.isNamed()) {
      for (final WeakReference<Object> helperModuleReference : helperModules) {
        final Object realModule = helperModuleReference.get();
        if (realModule != null) {
          final JavaModule helperModule = JavaModule.of(realModule);

          if (!target.canRead(helperModule)) {
            log.debug("Adding module read from {} to {}", target, helperModule);
            ClassInjector.UsingInstrumentation.redefineModule(
                Utils.getInstrumentation(),
                target,
                Collections.singleton(helperModule),
                Collections.<String, Set<JavaModule>>emptyMap(),
                Collections.<String, Set<JavaModule>>emptyMap(),
                Collections.<Class<?>>emptySet(),
                Collections.<Class<?>, List<Class<?>>>emptyMap());
          }
        }
      }
    }
  }

  private static File createTempDir() throws IOException {
    try {
      return Files.createTempDirectory(DATADOG_TEMP_JARS).toFile();
    } catch (final IOException e) {
      if (log.isErrorEnabled()) {
        log.error(
            "Unable to create temporary folder for injection.  Please ensure that `{}` specified by the system property `java.io.tmpdir` exists and is writable by this process",
            System.getProperty("java.io.tmpdir"));
      }

      throw e;
    }
  }

  private static void deleteTempDir(final File file) {
    // Not using Files.delete for deleting the directory because failures
    // create Exceptions which may prove expensive.  Instead using the
    // older File API which simply returns a boolean.
    final boolean deleted = file.delete();
    if (!deleted) {
      file.deleteOnExit();
      log.debug("file '{}' added to shutdown delete hook", file);
    } else {
      log.debug("file '{}' deleted", file);
    }
  }

  private static final String DATADOG_TEMP_JARS = "datadog-temp-jars";
  private static final int MAX_CLEANUP_MILLIS = 1_000;

  private static void cleanTempJars() {
    try {
      final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
      log.debug("Cleaning temp jar directories under {}", tmpDir);

      final long maxTimeMillis = System.currentTimeMillis() + MAX_CLEANUP_MILLIS;
      try (final DirectoryStream<Path> paths =
          Files.newDirectoryStream(tmpDir, DATADOG_TEMP_JARS + "*")) {
        for (final Path dir : paths) {
          if (System.currentTimeMillis() > maxTimeMillis) {
            break; // avoid attempting too much cleanup on boot
          }
          cleanTempJars(dir.toFile());
        }
      }
    } catch (final Throwable e) {
      log.debug("Problem cleaning temp jar directories", e);
    }
  }

  private static void cleanTempJars(final File tempJarDir) {
    final File[] tempJars =
        tempJarDir.listFiles(
            new FilenameFilter() {
              @Override
              public boolean accept(final File dir, final String name) {
                return name.startsWith("jar") && name.endsWith(".jar");
              }
            });

    if (tempJars != null) {
      for (final File jar : tempJars) {
        if (jar.delete()) {
          log.debug("file '{}' deleted", jar);
        }
      }
    }

    if (tempJarDir.delete()) {
      log.debug("file '{}' deleted", tempJarDir);
    }
  }
}
