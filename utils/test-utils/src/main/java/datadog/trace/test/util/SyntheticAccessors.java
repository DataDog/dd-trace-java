package datadog.trace.test.util;

import static java.util.Collections.list;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds the synthetic accessors javac generates when classes in one nest use each other's {@code
 * private} members. Backs the checker contract of {@code
 * datadog.perfcontract.NoSyntheticAccessors}.
 */
public final class SyntheticAccessors {
  private SyntheticAccessors() {}

  /** Fails if any class in {@code topLevel}'s nest has a synthetic accessor. */
  public static void assertNone(Class<?> topLevel) {
    List<String> accessors = find(topLevel);
    if (!accessors.isEmpty()) {
      throw new AssertionError(
          "Synthetic accessors in the nest of "
              + topLevel.getName()
              + "; make the accessed members package-private:\n  "
              + String.join("\n  ", accessors));
    }
  }

  /**
   * Lists the synthetic accessors in {@code topLevel}'s nest: methods named {@code access$*} and
   * synthetic constructors, each as {@code Class.member}.
   */
  public static List<String> find(Class<?> topLevel) {
    if (topLevel.getEnclosingClass() != null) {
      throw new IllegalArgumentException("not a top-level class: " + topLevel.getName());
    }
    List<String> accessors = new ArrayList<>();
    for (Class<?> nestMember : nestMembers(topLevel)) {
      for (Method method : nestMember.getDeclaredMethods()) {
        if (method.isSynthetic() && method.getName().startsWith("access$")) {
          accessors.add(nestMember.getName() + "." + method.getName());
        }
      }
      for (Constructor<?> constructor : nestMember.getDeclaredConstructors()) {
        if (constructor.isSynthetic()) {
          accessors.add(nestMember.getName() + ".<init>" + constructor.getParameterCount());
        }
      }
    }
    return accessors;
  }

  /**
   * Loads {@code topLevel} and every class file named {@code TopLevel$*} beside it. Anonymous and
   * local classes aren't reachable through reflection, so the class files are listed directly.
   */
  private static List<Class<?>> nestMembers(Class<?> topLevel) {
    String binaryPrefix = topLevel.getName() + "$";
    String filePrefix = topLevel.getSimpleName() + "$";
    URL url = topLevel.getResource(topLevel.getSimpleName() + ".class");
    if (url == null) {
      throw new IllegalStateException("no class file for " + topLevel.getName());
    }
    List<String> nestedNames = new ArrayList<>();
    try {
      if ("file".equals(url.getProtocol())) {
        File[] files = new File(url.toURI()).getParentFile().listFiles();
        if (files != null) {
          for (File file : files) {
            addNestedName(file.getName(), filePrefix, binaryPrefix, nestedNames);
          }
        }
      } else if ("jar".equals(url.getProtocol())) {
        String entryDir = topLevel.getName().replace('.', '/');
        entryDir = entryDir.substring(0, entryDir.lastIndexOf('/') + 1);
        JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
        for (JarEntry entry : list(jar.entries())) {
          String name = entry.getName();
          if (name.startsWith(entryDir) && name.indexOf('/', entryDir.length()) < 0) {
            addNestedName(name.substring(entryDir.length()), filePrefix, binaryPrefix, nestedNames);
          }
        }
      } else {
        throw new IllegalStateException("unsupported class location: " + url);
      }
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException("cannot list the nest of " + topLevel.getName(), e);
    }

    List<Class<?>> members = new ArrayList<>();
    members.add(topLevel);
    ClassLoader loader = topLevel.getClassLoader();
    for (String name : nestedNames) {
      try {
        members.add(Class.forName(name, false, loader));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("cannot load nest member " + name, e);
      }
    }
    return members;
  }

  private static void addNestedName(
      String fileName, String filePrefix, String binaryPrefix, List<String> nestedNames) {
    if (fileName.startsWith(filePrefix) && fileName.endsWith(".class")) {
      String suffix = fileName.substring(filePrefix.length(), fileName.length() - 6);
      nestedNames.add(binaryPrefix + suffix);
    }
  }
}
