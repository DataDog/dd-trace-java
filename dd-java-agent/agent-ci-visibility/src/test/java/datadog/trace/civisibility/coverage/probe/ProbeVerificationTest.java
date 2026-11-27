package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Instruments every class of a third-party jar and links it, which runs the JVM's verifier on the
 * instrumented code. The jars cover the bytecode of javac, kotlinc, scalac and groovyc.
 */
class ProbeVerificationTest {

  /** A time long past, given to cache entries so that writing one again shows. */
  private static final FileTime WRITTEN_AT = FileTime.fromMillis(1_000_000_000_000L);

  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "kotlin/Unit.class",
        "kotlinx/coroutines/Job.class",
        "scala/Option.class",
        "groovy/lang/GroovyObject.class",
        "okhttp3/OkHttpClient.class",
        "okio/Buffer.class",
        "org/jacoco/core/JaCoCo.class",
        "freemarker/template/Template.class",
        "com/fasterxml/jackson/databind/ObjectMapper.class",
        "org/mockito/Mockito.class",
        "org/spockframework/runtime/model/FeatureInfo.class",
        "org/objectweb/asm/ClassReader.class",
      })
  void instrumentedClassesPassVerification(String markerClass) throws Exception {
    JarFile jar = jar(markerClass);
    linkAll(jar, new InstrumentingClassLoader(jar, getClass().getClassLoader(), null));
  }

  /** Fills a class cache with the classes of a jar, then links them from the cache. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "kotlin/Unit.class",
        "scala/Option.class",
        "groovy/lang/GroovyObject.class",
        "okio/Buffer.class",
        "com/fasterxml/jackson/databind/ObjectMapper.class",
      })
  void classesFromTheClassCachePassVerification(String markerClass) throws Exception {
    JarFile jar = jar(markerClass);
    ProbeClassCache cache =
        ProbeClassCache.open(temporaryDirectory.toFile(), new File(jar.getName()));
    assertNotNull(cache);
    linkAll(jar, new InstrumentingClassLoader(jar, getClass().getClassLoader(), cache));
    List<Path> entries;
    try (Stream<Path> files = Files.walk(temporaryDirectory)) {
      entries = files.filter(Files::isRegularFile).collect(Collectors.toList());
    }
    assertFalse(entries.isEmpty(), "no class was cached");
    assertEquals(0, cache.writeFailureCount());
    for (Path entry : entries) {
      Files.setLastModifiedTime(entry, WRITTEN_AT);
    }

    linkAll(jar, new InstrumentingClassLoader(jar, getClass().getClassLoader(), cache));

    // a class whose entry cannot be used is patched again and written again
    List<Path> writtenAgain = new ArrayList<>();
    for (Path entry : entries) {
      if (!WRITTEN_AT.equals(Files.getLastModifiedTime(entry))) {
        writtenAgain.add(entry);
      }
    }
    assertEquals(new ArrayList<>(), writtenAgain);
    assertEquals(0, cache.writeFailureCount());
  }

  private JarFile jar(String markerClass) throws IOException {
    URL marker = getClass().getClassLoader().getResource(markerClass);
    assumeTrue(marker != null && "jar".equals(marker.getProtocol()), "not on the classpath");
    return ((JarURLConnection) marker.openConnection()).getJarFile();
  }

  /** Loads and links every class of the jar with the loader, and checks that all pass. */
  private static void linkAll(JarFile jar, InstrumentingClassLoader loader)
      throws ClassNotFoundException {
    List<String> verificationFailures = new ArrayList<>();
    int linked = 0;
    for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
      String entry = entries.nextElement().getName();
      if (!entry.endsWith(".class")
          || entry.startsWith("META-INF/")
          || entry.endsWith("module-info.class")
          || entry.endsWith("package-info.class")) {
        continue;
      }
      String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
      try {
        Class.forName(className, false, loader).getDeclaredMethods(); // links the class
        linked++;
      } catch (UnsupportedClassVersionError e) {
        // the class needs a newer JVM
      } catch (VerifyError | ClassFormatError e) {
        verificationFailures.add(className + ": " + e);
      } catch (LinkageError | SecurityException e) {
        // a dependency of the jar is missing
      }
    }

    assertEquals(new ArrayList<>(), verificationFailures);
    assertEquals(new ArrayList<>(), loader.instrumentationFailures);
    assertTrue(loader.instrumented > 0, "no class was instrumented");
    assertTrue(linked > 0, "no class was linked");
  }

  /** Defines the classes of one jar from instrumented bytes; everything else comes from parent. */
  private static final class InstrumentingClassLoader extends ClassLoader {
    private final JarFile jar;
    @Nullable private final ProbeClassCache cache;
    final List<String> instrumentationFailures = new ArrayList<>();
    int instrumented;

    InstrumentingClassLoader(JarFile jar, ClassLoader parent, @Nullable ProbeClassCache cache) {
      super(parent);
      this.jar = jar;
      this.cache = cache;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        JarEntry entry = jar.getJarEntry(name.replace('.', '/') + ".class");
        if (entry == null || name.startsWith("java.")) {
          return super.loadClass(name, resolve);
        }
        byte[] original;
        try (InputStream in = jar.getInputStream(entry)) {
          original = readAll(in);
        } catch (IOException e) {
          throw new ClassNotFoundException(name, e);
        }
        byte[] bytes = original;
        try {
          int majorVersion = ((original[6] & 0xFF) << 8) | (original[7] & 0xFF);
          if (majorVersion >= 49) {
            byte[] result =
                ProbeClassTransformer.instrument(original, this, majorVersion >= 50, cache);
            if (result != null) {
              bytes = result;
              instrumented++;
            }
          }
        } catch (Throwable e) {
          StackTraceElement[] frames = e.getStackTrace();
          instrumentationFailures.add(
              name
                  + ": "
                  + e
                  + " at "
                  + Arrays.asList(frames).subList(0, Math.min(frames.length, 6)));
        }
        return defineClass(name, bytes, 0, bytes.length);
      }
    }

    private static byte[] readAll(InputStream in) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }
}
