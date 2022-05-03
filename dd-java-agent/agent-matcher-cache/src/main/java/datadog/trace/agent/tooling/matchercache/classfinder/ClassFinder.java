package datadog.trace.agent.tooling.matchercache.classfinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.bytebuddy.jar.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClassFinder {
  private static final Logger log = LoggerFactory.getLogger(ClassFinder.class);

  public ClassCollection findClassesIn(File classPath) throws IOException {
    ClassCollection classCollection = new ClassCollection();
    findClassesIn(classPath, classCollection);
    return classCollection;
  }

  public void findClassesIn(File classPath, ClassCollection outClassCollection) throws IOException {
    if (!classPath.exists()) {
      log.warn("Class path {} doesn't exist.", classPath);
    } else if (isJar(classPath.getName())) {
      log.debug("Exploring archive: {}", classPath);
      try (InputStream is = Files.newInputStream(classPath.toPath())) {
        scanJarFile(classPath.toString(), is, outClassCollection);
      }
    } else if (classPath.isDirectory()) {
      log.debug("Exploring directory: {}", classPath);
      scanDirectory(classPath, outClassCollection);
    } else {
      log.warn(
          "Only folders, jar or jmod files are allowed as a classpath. {} neither folder or jar.",
          classPath);
    }
  }

  private void scanJarFile(String parentPath, InputStream is, ClassCollection classCollection)
      throws IOException {
    try (JarInputStream jarInputStream = new JarInputStream(is)) {
      JarEntry jarEntry;
      while ((jarEntry = jarInputStream.getNextJarEntry()) != null) {
        String entryPath = jarEntry.getName();
        if (isJar(entryPath)) {
          log.debug("Exploring archive: {}", entryPath);
          try (ByteArrayOutputStream archiveStream = new ByteArrayOutputStream()) {
            copyInputStreamToOutputStream(jarInputStream, archiveStream);
            try (ByteArrayInputStream archiveStreamIS =
                new ByteArrayInputStream(archiveStream.toByteArray())) {
              scanJarFile(parentPath + "/" + entryPath, archiveStreamIS, classCollection);
            }
          }
        } else if (readIfClass(parentPath, entryPath, jarInputStream, classCollection)) {
          log.debug("Found class: {}", entryPath);
        }
      }
    }
  }

  private void scanDirectory(File directory, ClassCollection classCollection) throws IOException {
    File[] files = directory.listFiles();
    if (files == null) {
      throw new IllegalStateException("No files found in " + directory);
    }
    for (File file : files) {
      if (file.isDirectory()) {
        scanDirectory(file, classCollection);
      } else if (isJar(file.getName())) {
        findClassesIn(file, classCollection);
      } else if (file.getName().endsWith(".jmod")) {
        scanModule(file, classCollection);
      } else {
        try {
          String relativeName = directory.toURI().relativize(file.toURI()).getPath();
          try (InputStream fileInputStream = new FileInputStream(file)) {
            readIfClass(file.toString(), relativeName, fileInputStream, classCollection);
          }
        } catch (MalformedURLException | FileNotFoundException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private void scanModule(File cp, ClassCollection classCollection) {
    try (ZipFile zipFile = new ZipFile(cp)) {
      for (Enumeration<? extends ZipEntry> entries = zipFile.entries();
          entries.hasMoreElements(); ) {
        ZipEntry zipEntry = entries.nextElement();
        String name = zipEntry.getName();
        try (InputStream is = zipFile.getInputStream(zipEntry)) {
          readIfClass(cp.toString(), name, is, classCollection);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private boolean isJar(String path) {
    if (path.endsWith(".war") || path.endsWith(".ear")) {
      log.warn("Found not supported class archive format: {}", path);
      // TODO add support (need to be tested)
    }
    return path.endsWith(".jar");
  }

  private static final byte[] CLASS_FILE_MAGIC_HEADER =
      new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

  private boolean readIfClass(
      String parentPath, String relativePath, InputStream is, ClassCollection classCollection)
      throws IOException {
    byte[] classBytes = readInputStreamToByteArray(is, CLASS_FILE_MAGIC_HEADER);
    if (classBytes != null) {
      try {
        String className = readClassName(classBytes);
        // TODO do we ever transform classes in *.classdata? Remove if not.
        if (!"module-info".equals(className)
            && !"package-info".equals(className)
            && !relativePath.endsWith(".classdata")) {
          classCollection.addClass(classBytes, className, relativePath, parentPath);
          return true;
        }
      } catch (Throwable t) {
        log.debug("Can't read class: {}", relativePath);
      }
    }
    return false;
  }

  private byte[] readInputStreamToByteArray(InputStream is, byte[] expectedHeader)
      throws IOException {
    byte[] classBytes;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] magicHeader = new byte[expectedHeader.length];
      if (is.read(magicHeader) < expectedHeader.length
          || !Arrays.equals(magicHeader, expectedHeader)) {
        // not a class file
        return null;
      }
      out.write(expectedHeader);
      copyInputStreamToOutputStream(is, out);
      classBytes = out.toByteArray();
    }
    return classBytes;
  }

  private static void copyInputStreamToOutputStream(InputStream is, OutputStream out)
      throws IOException {
    byte[] buffer = new byte[2048];
    int readBytes;
    while ((readBytes = is.read(buffer)) > 0) {
      out.write(buffer, 0, readBytes);
    }
  }

  private static String readClassName(byte[] classBytes) {
    ClassReader cr = new ClassReader(classBytes);
    return cr.getClassName().replace("/", ".");
  }
}
