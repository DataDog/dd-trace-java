package datadog.smoketest.classloader;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple class loader that defines classes without a protection domain (actually without a {@link
 * java.security.CodeSource CodeSource}, to mimic IntelliJ's class loading behavior).
 */
@SuppressWarnings("unused")
public class NoProtectionDomainClassLoader extends URLClassLoader {
  private final Path[] classPath;

  private final Map<String, Class<?>> classCache = new HashMap<>();

  /**
   * Packages that should be loaded by the parent class loader. There's likely more for a proper
   * implementation like org.w3c. but not necessary for this test.
   */
  private final String[] excludedPackages =
      new String[] {
        "java.", "javax.", "jdk.", "sun.", "oracle.", "com.sun.", "com.ibm.", "COM.ibm.",
      };

  /**
   * Used by system to inject the default class loader.
   *
   * @param parent Parent classloader
   * @see ClassLoader#getSystemClassLoader
   */
  @SuppressForbidden
  public NoProtectionDomainClassLoader(ClassLoader parent) {
    super(new URL[0], parent);
    // Stream equivalent is not possible for system classloader for JDK 8
    List<Path> list = new ArrayList<>();
    for (String s : System.getProperty("java.class.path").split(File.pathSeparator)) {
      Path path = Paths.get(s);
      list.add(path);
    }
    classPath = list.toArray(new Path[0]);
  }

  /**
   * Loads the class with the specified name. JDK classes are loaded in the parent classloader,
   * others by this classloader.
   *
   * @param name The binary name of the class
   * @param resolve If true, then resolve the class
   * @return The loaded class
   * @throws ClassNotFoundException if the class is not found
   */
  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    for (String excludedPackage : excludedPackages) {
      if (name.startsWith(excludedPackage)) {
        return super.loadClass(name, resolve);
      }
    }
    Class<?> cls = classCache.get(name);
    if (cls != null) {
      return cls;
    }
    return findClass(name);
  }

  /**
   * Finds and loads the class with the specified name from the URL search path without {@link
   * ProtectionDomain}. Any URLs referring to JAR files are loaded and opened as needed until the
   * class is found.
   *
   * @param name the binary name of the class
   * @return The defined class
   * @throws ClassNotFoundException Thrown on any IO or if actually not found
   */
  public Class<?> findClass(String name) throws ClassNotFoundException {

    String classFile = name.replace('.', '/') + ".class";
    try (InputStream classData = getResourceAsStream(classFile)) {
      byte[] array = readClassBytes(name, classData, classFile);

      // Since this class is used as the system class loader, we need to delegate
      // to the parent class loader, otherwise **this** class maybe loaded by again itself, thus
      // resulting in defining different classes making `instanceof` checks fail.
      if (name.equals(getClass().getCanonicalName())) {
        return getParent().loadClass(name);
      }

      Class<?> aClass = defineClass(name, array, 0, array.length, (ProtectionDomain) null);
      classCache.put(name, aClass);
      return aClass;
    } catch (IOException io) {
      throw new ClassNotFoundException("Loading class failed", io);
    }
  }

  /**
   * Reads all bytes from the class file or from an input stream.
   *
   * @param name The binary name of the class
   * @param classData The stream of the class bytes, nullable
   * @param classFile The relative path to the class file, nullable
   * @return The class bytes
   * @throws IOException Thrown on failure to read the class bytes
   * @throws ClassNotFoundException Thrown if the class file doesn't exist and classFile is null
   */
  private byte[] readClassBytes(String name, InputStream classData, String classFile)
      throws IOException, ClassNotFoundException {
    if (classData == null) {
      // try local classpath
      for (Path path : classPath) {
        Path resolvedPath = path.resolve(classFile);
        if (Files.exists(resolvedPath)) {
          return Files.readAllBytes(resolvedPath);
        }
      }
      throw new ClassNotFoundException("Couldn't find class " + name);
    }

    return readAllBytes(classData);
  }

  /** Straw man {@code readAllBytes} for pre-JDK 9. */
  private static byte[] readAllBytes(final InputStream resource) throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int bytesRead;
    final byte[] data = new byte[1024];
    while ((bytesRead = resource.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, bytesRead);
    }
    buffer.flush();
    return buffer.toByteArray();
  }

  /**
   * Called by the VM to support dynamic additions to the class path.
   *
   * @see java.lang.instrument.Instrumentation#appendToSystemClassLoaderSearch
   */
  @SuppressWarnings("unused")
  final void appendToClassPathForInstrumentation(String jar) throws IOException {
    addURL(Paths.get(jar).toRealPath().toFile().toURI().toURL());
  }
}
