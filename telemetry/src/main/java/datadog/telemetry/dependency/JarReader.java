package datadog.telemetry.dependency;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
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

    public Extracted(
        final String jarName,
        final Map<String, Properties> pomProperties,
        final Attributes manifest) {
      this.jarName = jarName;
      this.pomProperties = pomProperties;
      this.manifest = manifest;
    }
  }

  public static Extracted readJarFile(String jarPath) throws IOException {
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
      return new Extracted(new File(jar.getName()).getName(), pomProperties, attributes);
    }
  }

  public static Extracted readNestedJarFile(final String jarPath) throws IOException {
    final int sepIdx = jarPath.indexOf("!/");
    if (sepIdx == -1) {
      throw new IllegalArgumentException("Invalid nested jar path: " + jarPath);
    }
    final String outerJarPath = jarPath.substring(0, sepIdx);
    String innerJarPath = jarPath.substring(sepIdx + 2);
    if (innerJarPath.endsWith("!/")) {
      innerJarPath = innerJarPath.substring(0, innerJarPath.length() - 2);
    }
    try (final JarFile outerJar = new JarFile(outerJarPath, false /* no verify */)) {
      final ZipEntry entry = outerJar.getEntry(innerJarPath);
      if (entry == null) {
        throw new NoSuchFileException("Nested jar not found: " + jarPath);
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
        return new Extracted(new File(innerJarPath).getName(), pomProperties, attributes);
      }
    }
  }
}
