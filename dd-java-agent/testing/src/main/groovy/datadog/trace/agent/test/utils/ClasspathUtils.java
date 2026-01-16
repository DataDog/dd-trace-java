package datadog.trace.agent.test.utils;

import static datadog.trace.util.Strings.getResourceName;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ClasspathUtils {

  public static byte[] convertToByteArray(final InputStream resource) throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int bytesRead;
    final byte[] data = new byte[1024];
    while ((bytesRead = resource.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, bytesRead);
    }
    buffer.flush();
    return buffer.toByteArray();
  }

  public static byte[] convertToByteArray(final Class<?> clazz) throws IOException {
    try (InputStream inputStream =
        clazz.getClassLoader().getResourceAsStream(getResourceName(clazz.getName()))) {
      return convertToByteArray(inputStream);
    }
  }

  /**
   * Create a temporary jar on the filesystem with the bytes of the given classes.
   *
   * <p>The jar file will be removed when the jvm exits.
   *
   * @param loader classloader used to load bytes
   * @param resourceNames names of resources to copy into the new jar
   * @return the location of the newly created jar.
   * @throws IOException if the jar file cannot be created.
   */
  @SuppressForbidden
  public static URL createJarWithClasses(final ClassLoader loader, final String... resourceNames)
      throws IOException {
    final File tmpJar = File.createTempFile(UUID.randomUUID().toString(), ".jar");
    tmpJar.deleteOnExit();

    final Manifest manifest = new Manifest();
    try (final JarOutputStream target =
        new JarOutputStream(
            new BufferedOutputStream(Files.newOutputStream(tmpJar.toPath())), manifest)) {
      for (final String resourceName : resourceNames) {
        try (InputStream is = loader.getResourceAsStream(resourceName)) {
          if (is != null) {
            addToJar(resourceName, convertToByteArray(is), target);
          }
        }
      }
    }

    return tmpJar.toURI().toURL();
  }

  /**
   * Create a temporary jar on the filesystem with the bytes of the given classes.
   *
   * <p>The jar file will be removed when the jvm exits.
   *
   * @param classes classes to package into the jar.
   * @return the location of the newly created jar.
   * @throws IOException
   */
  @SuppressForbidden
  public static URL createJarWithClasses(final Class<?>... classes) throws IOException {
    final File tmpJar = File.createTempFile(UUID.randomUUID().toString(), ".jar");
    tmpJar.deleteOnExit();

    final Manifest manifest = new Manifest();
    final JarOutputStream target =
        new JarOutputStream(
            new BufferedOutputStream(Files.newOutputStream(tmpJar.toPath())), manifest);
    for (final Class<?> clazz : classes) {
      addToJar(getResourceName(clazz.getName()), convertToByteArray(clazz), target);
    }
    target.close();

    return tmpJar.toURI().toURL();
  }

  private static void addToJar(
      final String resourceName, final byte[] bytes, final JarOutputStream jarOutputStream)
      throws IOException {
    final JarEntry entry = new JarEntry(resourceName);
    jarOutputStream.putNextEntry(entry);
    jarOutputStream.write(bytes, 0, bytes.length);
    jarOutputStream.closeEntry();
  }

  // Moved this to a java class because groovy was adding a hard ref to classLoader
  public static boolean isClassLoaded(final String className, final ClassLoader classLoader) {
    try {
      final Method findLoadedClassMethod =
          ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
      try {
        findLoadedClassMethod.setAccessible(true);
        final Class<?> loadedClass =
            (Class<?>) findLoadedClassMethod.invoke(classLoader, className);
        return null != loadedClass && loadedClass.getClassLoader() == classLoader;
      } catch (final Exception e) {
        throw new IllegalStateException(e);
      } finally {
        findLoadedClassMethod.setAccessible(false);
      }
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
