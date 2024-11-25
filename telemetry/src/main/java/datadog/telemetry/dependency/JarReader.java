package datadog.telemetry.dependency;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

class JarReader {
  static class Extracted {
    final String jarName;
    final Map<String, Properties> pomProperties;
    final Attributes manifest;
    final boolean isDirectory;
    final InputStreamSupplier inputStreamSupplier;

    public Extracted(
        final String jarName,
        final Map<String, Properties> pomProperties,
        final Attributes manifest,
        final boolean isDirectory,
        final InputStreamSupplier inputStreamSupplier) {
      this.jarName = jarName;
      this.pomProperties = pomProperties;
      this.manifest = manifest;
      this.isDirectory = isDirectory;
      this.inputStreamSupplier = inputStreamSupplier;
    }

    public interface InputStreamSupplier {
      InputStream get() throws IOException;
    }
  }

  public static Extracted readJarFile(String jarPath) throws IOException {
    final File jarFile = new File(jarPath);
    if (jarFile.isDirectory()) {
      return new Extracted(jarFile.getName(), new HashMap<>(), new Attributes(), true, () -> null);
    }
    try (final JarFile jar = new JarFile(jarPath, false /* no verify */)) {
      final Map<String, Properties> pomProperties = new HashMap<>();
      final Enumeration<? extends ZipEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        final ZipEntry entry = entries.nextElement();
        if (entry.getName().endsWith("pom.properties")) {
          try (final InputStream is = jar.getInputStream(entry)) {
            final Properties properties = new Properties();
            properties.load(is);
            pomProperties.put(entry.getName(), properties);
          }
        }
      }
      final Manifest manifest = jar.getManifest();
      final Attributes attributes =
          (manifest == null) ? new Attributes() : manifest.getMainAttributes();
      return new Extracted(
          new File(jar.getName()).getName(),
          pomProperties,
          attributes,
          false,
          () -> Files.newInputStream(Paths.get(jarPath)));
    }
  }

  public static Extracted readNestedJarFile(final String outerJarPath, final String innerJarPath)
      throws IOException {
    try (final JarFile outerJar = new JarFile(outerJarPath, false /* no verify */)) {
      final ZipEntry entry = outerJar.getEntry(innerJarPath);
      if (entry == null) {
        throw new NoSuchFileException("Nested jar not found: " + innerJarPath);
      }
      if (entry.isDirectory()) {
        return new Extracted(
            new File(innerJarPath).getName(), new HashMap<>(), new Attributes(), true, () -> null);
      }
      try (final InputStream is = outerJar.getInputStream(entry);
          final JarInputStream innerJar = new JarInputStream(is, false /* no verify */)) {
        final Map<String, Properties> pomProperties = new HashMap<>();
        ZipEntry innerEntry;
        while ((innerEntry = innerJar.getNextEntry()) != null) {
          if (innerEntry.getName().endsWith("pom.properties")) {
            final Properties properties = new Properties();
            properties.load(innerJar);
            pomProperties.put(innerEntry.getName(), properties);
          }
        }
        final Manifest manifest = innerJar.getManifest();
        final Attributes attributes =
            (manifest == null) ? new Attributes() : manifest.getMainAttributes();
        return new Extracted(
            new File(innerJarPath).getName(),
            pomProperties,
            attributes,
            false,
            () -> new NestedJarInputStream(outerJarPath, innerJarPath));
      }
    }
  }

  static class NestedJarInputStream extends InputStream implements AutoCloseable {
    private final JarFile outerJar;
    private final InputStream innerInputStream;

    public NestedJarInputStream(final String outerPath, final String innerPath) throws IOException {
      super();
      this.outerJar = new JarFile(outerPath, false /* no verify */);
      final ZipEntry entry = outerJar.getEntry(innerPath);
      this.innerInputStream = outerJar.getInputStream(entry);
    }

    @Override
    public int read() throws IOException {
      return this.innerInputStream.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return this.innerInputStream.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
      this.innerInputStream.close();
      this.outerJar.close();
    }
  }
}
