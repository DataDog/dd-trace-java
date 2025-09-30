package datadog.trace.agent.test;

import static java.io.File.pathSeparator;

import com.google.common.reflect.ClassPath;
import datadog.trace.agent.test.utils.ClasspathUtils;
import datadog.trace.bootstrap.BootstrapProxy;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * This class appends tracer classes from the packages listed in {@link #TEST_BOOTSTRAP_PREFIXES} to
 * bootstrap classpath.
 *
 * <p>This needs to be done before these classes are loaded by the application classloader. JUnit 5
 * does classpath scanning in order to discover tests, so this class has to be initialized early in
 * JUnit 5 lifecycle (before classpath scanning takes place). It implements {@link
 * LauncherSessionListener} as the latter is called early enough. Invoking the listener triggers
 * class initialization and bootstrap classpath setup.
 *
 * <p><strong>IMPORTANT:</strong> the listener is loaded through the ServiceLoader mechanism, so if
 * this class (and the corresponding {@code org.junit.platform.launcher.LauncherSessionListener}
 * file) is on the classpath, it will be called and the bootstrap classpath will be patched!
 */
public class BootstrapClasspathSetupListener implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    // this method is only needed to trigger this class' static initializer before JUnit does
    // classpath scanning
  }

  private static final String[] TEST_EXCLUDED_BOOTSTRAP_PACKAGE_PREFIXES = {
    "ch.qos.logback.classic.servlet", // this draws javax.servlet deps that are not needed
  };

  private static final String[] TEST_BOOTSTRAP_PREFIXES;

  /**
   * An exact copy of {@link datadog.trace.bootstrap.Constants#BOOTSTRAP_PACKAGE_PREFIXES}.
   *
   * <p>This list is needed to initialize the bootstrap classpath because Utils' static initializer
   * references bootstrap classes (e.g. DatadogClassLoader).
   */
  public static final String[] BOOTSTRAP_PACKAGE_PREFIXES_COPY = {
    "datadog.slf4j",
    "datadog.context",
    "datadog.environment",
    "datadog.json",
    "datadog.yaml",
    "datadog.instrument",
    "datadog.appsec.api",
    "datadog.trace.api",
    "datadog.trace.bootstrap",
    "datadog.trace.context",
    "datadog.trace.instrumentation.api",
    "datadog.trace.logging",
    "datadog.trace.util",
    "datadog.trace.config.inversion", // Add this line
  };

  public static final ClassPath TEST_CLASSPATH = computeTestClasspath();

  // matches names ending with Test and inner classes (e.g. MyTest$1, MyTest$InnerClass,
  // MyTest$InnerClass$2, etc)
  private static final Pattern TEST_CLASS_PATTERN = Pattern.compile(".*Test(\\$\\w+)*$");

  static {
    TEST_BOOTSTRAP_PREFIXES =
        Arrays.copyOf(BOOTSTRAP_PACKAGE_PREFIXES_COPY, BOOTSTRAP_PACKAGE_PREFIXES_COPY.length + 2);
    TEST_BOOTSTRAP_PREFIXES[BOOTSTRAP_PACKAGE_PREFIXES_COPY.length] = "org.slf4j";
    TEST_BOOTSTRAP_PREFIXES[BOOTSTRAP_PACKAGE_PREFIXES_COPY.length + 1] = "ch.qos.logback";

    ByteBuddyAgent.install();
    setupBootstrapClasspath();
  }

  private static ClassPath computeTestClasspath() {
    ClassLoader testClassLoader = BootstrapClasspathSetupListener.class.getClassLoader();
    if (!(testClassLoader instanceof URLClassLoader)) {
      // java9's system loader does not extend URLClassLoader
      // which breaks Guava ClassPath lookup
      testClassLoader = buildJavaClassPathClassLoader();
    }
    try {
      return ClassPath.from(testClassLoader);
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Parse JVM classpath and return ClassLoader containing all classpath entries. Inspired by Guava.
   */
  @SuppressForbidden
  private static ClassLoader buildJavaClassPathClassLoader() {
    List<URL> urls = new ArrayList<>();
    String classPath = System.getProperty("java.class.path", "");
    for (String entry : classPath.split(pathSeparator)) {
      try {
        File pathEntry = new File(entry);
        try {
          urls.add(pathEntry.toURI().toURL());
        } catch (final SecurityException e) {
          urls.add(new URL("file", null, pathEntry.getAbsolutePath()));
        }
      } catch (final MalformedURLException e) {
        System.err.printf(
            "Error injecting bootstrap jar: Malformed classpath entry: %s. %s%n", entry, e);
      }
    }
    return new URLClassLoader(urls.toArray(new URL[0]), null);
  }

  private static void setupBootstrapClasspath() {
    // Ensure there weren't any bootstrap classes loaded prematurely.
    Set<String> prematureBootstrapClasses = new TreeSet<>();
    for (Class<?> clazz : ByteBuddyAgent.getInstrumentation().getAllLoadedClasses()) {
      if (isBootstrapClass(clazz)
          && clazz.getClassLoader() != null
          && !clazz.getName().equals("datadog.trace.api.DisableTestTrace")
          && !clazz.getName().startsWith("org.slf4j")) {
        prematureBootstrapClasses.add(clazz.getName());
      }
    }
    if (!prematureBootstrapClasses.isEmpty()) {
      throw new AssertionError(
          prematureBootstrapClasses.size()
              + " classes were loaded before bootstrap classpath was initialized: "
              + prematureBootstrapClasses);
    }
    try {
      final File bootstrapJar = createBootstrapJar();
      ByteBuddyAgent.getInstrumentation()
          .appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
      BootstrapProxy.addBootstrapResource(bootstrapJar.toURI().toURL());
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static File createBootstrapJar() throws IOException {
    Set<String> bootstrapClasses = new HashSet<>();
    for (ClassPath.ClassInfo info : TEST_CLASSPATH.getAllClasses()) {
      if (isBootstrapClass(info)) {
        bootstrapClasses.add(info.getResourceName());
      }
    }
    URL jar =
        ClasspathUtils.createJarWithClasses(
            TestClassShadowingExtension.class.getClassLoader(),
            bootstrapClasses.toArray(new String[0]));
    return new File(jar.getFile());
  }

  public static boolean isBootstrapClass(final ClassPath.ClassInfo info) {
    return isBootstrapClass(
        info,
        ClassPath.ClassInfo::getName,
        ClassPath.ClassInfo::getResourceName,
        ClassPath.ClassInfo::url);
  }

  public static boolean isBootstrapClass(final Class<?> clazz) {
    return !clazz.isPrimitive()
        && isBootstrapClass(
            clazz,
            Class::getName,
            BootstrapClasspathSetupListener::classToResourceName,
            BootstrapClasspathSetupListener::classToUrl);
  }

  private static final Map<String, String> CLASS_NAME_TO_RESOURCE_NAME = new HashMap<>();

  private static String classToResourceName(final Class<?> clazz) {
    return CLASS_NAME_TO_RESOURCE_NAME.computeIfAbsent(
        clazz.getName(), k -> k.replace('.', '/') + ".class");
  }

  private static URL classToUrl(final Class<?> clazz) {
    ClassLoader classLoader = clazz.getClassLoader();
    return classLoader == null ? null : classLoader.getResource(classToResourceName(clazz));
  }

  private static <T> boolean isBootstrapClass(
      final T type,
      final Function<T, String> toName,
      final Function<T, String> toResourceName,
      final Function<T, URL> toUrl) {
    String name = toName.apply(type);
    for (String prefix : TEST_BOOTSTRAP_PREFIXES) {
      if (name.startsWith(prefix)) {
        for (String excluded : TEST_EXCLUDED_BOOTSTRAP_PACKAGE_PREFIXES) {
          if (name.startsWith(excluded)) {
            return false;
          }
        }
        // Tests should be loaded by the application classloader.
        // If a test is loaded by the bootstrap classloader,
        // it will either fail during the test discovery phase
        // (if its method signatures contain classes only visible to the app classloader)
        // or will not be discovered at all
        // (because JUnit will be looking for annotation classes loaded by the app classloader)
        if (TEST_CLASS_PATTERN.matcher(name).matches()) {
          String resource = toResourceName.apply(type);
          URL url = toUrl.apply(type);
          if (isATest(resource, url)) {
            return false;
          }
        }
        return true;
      }
    }
    return false;
  }

  // A safety check to only consider classes from "test/latestDepTest/forkedTest/etc" source roots
  private static boolean isATest(String resourceName, URL url) {
    return url != null
        && (url.getPath().endsWith("test/" + resourceName)
            || url.getPath().endsWith("Test/" + resourceName));
  }
}
