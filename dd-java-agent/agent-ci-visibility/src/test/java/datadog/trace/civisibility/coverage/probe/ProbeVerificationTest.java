package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Instruments every class of a third-party jar and links it, which runs the JVM's verifier on the
 * instrumented code. The jars cover the bytecode of javac, kotlinc, scalac and groovyc.
 */
class ProbeVerificationTest {

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
    URL marker = getClass().getClassLoader().getResource(markerClass);
    assumeTrue(marker != null && "jar".equals(marker.getProtocol()), "not on the classpath");

    JarFile jar = ((JarURLConnection) marker.openConnection()).getJarFile();
    InstrumentingClassLoader loader =
        new InstrumentingClassLoader(jar, getClass().getClassLoader());
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
    final List<String> instrumentationFailures = new ArrayList<>();
    int instrumented;

    InstrumentingClassLoader(JarFile jar, ClassLoader parent) {
      super(parent);
      this.jar = jar;
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
            byte[] result = ProbeClassTransformer.instrument(original, this, majorVersion >= 50);
            if (result != null) {
              bytes = result;
              instrumented++;
            }
          }
        } catch (Throwable e) {
          instrumentationFailures.add(name + ": " + e);
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
