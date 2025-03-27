package datadog.trace.instrumentation.spark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClassLoaderUtils {
  public static final Logger log = LoggerFactory.getLogger(ClassLoaderUtils.class);

  /** Print the classloader hierarchy for the current thread */
  public static void printClassLoaderHierarchy() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    printClassLoaderHierarchy(loader);
  }

  /** Print the classloader hierarchy for a specific class */
  public static void printClassLoaderHierarchy(Class<?> clazz) {
    log.info("ClassLoader hierarchy for " + clazz.getName() + ":");
    printClassLoaderHierarchy(clazz.getClassLoader());
  }

  /** Print the classloader hierarchy starting from a specific classloader */
  public static void printClassLoaderHierarchy(ClassLoader classLoader) {
    if (classLoader == null) {
      log.info("Bootstrap ClassLoader (null)");
      return;
    }

    int level = 0;
    ClassLoader current = classLoader;

    while (current != null) {
      log.info(
          getIndent(level)
              + "→ "
              + current.getClass().getName()
              + "@"
              + Integer.toHexString(System.identityHashCode(current)));

      // Print URLs for URLClassLoaders
      //      if (current instanceof URLClassLoader) {
      //        URLClassLoader urlLoader = (URLClassLoader) current;
      //        for (URL url : urlLoader.getURLs()) {
      //          log.info(getIndent(level + 1) + "- " + url);
      //        }
      //      }

      current = current.getParent();
      level++;
    }

    log.info(getIndent(level) + "→ Bootstrap ClassLoader (null)");
  }

  private static String getIndent(int level) {
    StringBuilder indent = new StringBuilder();
    for (int i = 0; i < level; i++) {
      indent.append("  ");
    }
    return indent.toString();
  }
}
