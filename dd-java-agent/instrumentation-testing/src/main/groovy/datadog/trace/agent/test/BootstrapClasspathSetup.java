package datadog.trace.agent.test;

import static com.google.common.base.StandardSystemProperty.JAVA_CLASS_PATH;
import static com.google.common.base.StandardSystemProperty.PATH_SEPARATOR;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.ClassPath;
import datadog.trace.agent.test.utils.ClasspathUtils;
import datadog.trace.bootstrap.BootstrapProxy;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public class BootstrapClasspathSetup implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    // this method is only needed to trigger this class' static initializer before JUnit does
    // classpath scanning
  }

  private static final String[] TEST_EXCLUDED_BOOTSTRAP_PACKAGE_PREFIXES = {
    "ch.qos.logback.classic.servlet", // this draws javax.servlet deps that are not needed
  };

  private static final String[] TEST_BOOTSTRAP_PREFIXES = {
    "datadog.slf4j",
    "datadog.context",
    "datadog.environment",
    "datadog.json",
    "datadog.yaml",
    "datadog.appsec.api",
    "datadog.trace.api",
    "datadog.trace.bootstrap",
    "datadog.trace.context",
    "datadog.trace.instrumentation.api",
    "datadog.trace.logging",
    "datadog.trace.util",
    "org.slf4j",
    "ch.qos.logback",
  };

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
    "datadog.appsec.api",
    "datadog.trace.api",
    "datadog.trace.bootstrap",
    "datadog.trace.context",
    "datadog.trace.instrumentation.api",
    "datadog.trace.logging",
    "datadog.trace.util",
  };

  public static final ClassPath TEST_CLASSPATH = computeTestClasspath();

  // matches names ending with Test and inner classes (e.g. MyTest$1, MyTest$InnerClass,
  // MyTest$InnerClass$2, etc)
  private static final Pattern TEST_CLASS_PATTERN = Pattern.compile(".*Test(\\$\\w+)*$");

  static {
    ByteBuddyAgent.install();
    setupBootstrapClasspath();
  }

  private static ClassPath computeTestClasspath() {
    ClassLoader testClassLoader = AgentTestRunner.class.getClassLoader();
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
    final ImmutableList.Builder<URL> urls = ImmutableList.builder();
    for (final String entry : Splitter.on(PATH_SEPARATOR.value()).split(JAVA_CLASS_PATH.value())) {
      try {
        try {
          urls.add(new File(entry).toURI().toURL());
        } catch (final SecurityException e) { // File.toURI checks to see if the file is a directory
          urls.add(new URL("file", null, new File(entry).getAbsolutePath()));
        }
      } catch (final MalformedURLException e) {
        System.err.println(
            String.format(
                "Error injecting bootstrap jar: Malformed classpath entry: %s. %s", entry, e));
      }
    }
    return new URLClassLoader(urls.build().toArray(new URL[0]), null);
  }

  private static void setupBootstrapClasspath() {
    // Ensure there weren't any bootstrap classes loaded prematurely.
    Set<String> prematureBootstrapClasses = new TreeSet<>();
    for (Class clazz : ByteBuddyAgent.getInstrumentation().getAllLoadedClasses()) {
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
            SpockExtension.class.getClassLoader(), bootstrapClasses.toArray(new String[0]));
    return new File(jar.getFile());
  }

  public static boolean isBootstrapClass(final Class<?> clazz) {
    String resource = clazz.getName().replace('.', '/') + ".class";
    URL url = clazz.getClassLoader() != null ? clazz.getClassLoader().getResource(resource) : null;
    return !clazz.isPrimitive() && isBootstrapClass(clazz.getName(), resource, url);
  }

  public static boolean isBootstrapClass(final ClassPath.ClassInfo info) {
    return isBootstrapClass(info.getName(), info.getResourceName(), info.url());
  }

  private static boolean isBootstrapClass(
      final String name, final String resourceName, final URL url) {
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
        if (TEST_CLASS_PATTERN.matcher(name).matches() && isATest(resourceName, url)) {
          return false;
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
