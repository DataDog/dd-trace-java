package datadog.trace.civisibility.coverage.probe;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The classes of the recorder that probes call never get probes, whatever loader defines them and
 * whatever the filter accepts: a probe in them would call the recorder from inside it.
 */
class ProbeRecorderNotProbedTest {

  private static final String RECORDER_PACKAGE = "datadog/trace/api/civisibility/coverage/";

  @Test
  void noClassOfTheRecorderGetsProbes() throws Exception {
    List<String> recorderClasses = classesOfTheRecorder();
    assertTrue(
        recorderClasses.containsAll(
            asList(
                RECORDER_PACKAGE + "ProbeRecorder",
                RECORDER_PACKAGE + "ThreadProbes",
                RECORDER_PACKAGE + "ThreadIds",
                RECORDER_PACKAGE + "CoverageWindow")),
        recorderClasses.toString());
    ProbeClassTransformer transformer = new ProbeClassTransformer(className -> true);
    ClassLoader loader = new URLClassLoader(new URL[0], null);

    List<String> probed = new ArrayList<>();
    for (String className : recorderClasses) {
      byte[] classFile = classFile(className);
      if (transformer.transform(loader, className, null, null, classFile) != null
          || transformer.transform(null, className, null, null, classFile) != null) {
        probed.add(className);
      }
    }

    assertEquals(emptyList(), probed);
    String fixture = "datadog/trace/civisibility/coverage/probe/fixtures/AdviceTarget";
    assertNotNull(
        transformer.transform(loader, fixture, null, null, classFile(fixture)),
        "the transformer inserted no probes into a class outside the recorder");
  }

  /** The classes of the recorder's package, as internal names. */
  private static List<String> classesOfTheRecorder() throws Exception {
    URL location = ProbeRecorder.class.getProtectionDomain().getCodeSource().getLocation();
    File file = new File(location.toURI());
    List<String> classes = new ArrayList<>();
    if (file.isDirectory()) {
      File[] classFiles = new File(file, RECORDER_PACKAGE).listFiles();
      if (classFiles != null) {
        for (File classFile : classFiles) {
          addIfClass(classes, RECORDER_PACKAGE + classFile.getName());
        }
      }
    } else {
      try (JarFile jar = new JarFile(file)) {
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
          String name = entries.nextElement().getName();
          if (name.indexOf('/', RECORDER_PACKAGE.length()) < 0) {
            addIfClass(classes, name);
          }
        }
      }
    }
    return classes;
  }

  private static void addIfClass(List<String> classes, String path) {
    if (path.startsWith(RECORDER_PACKAGE) && path.endsWith(".class")) {
      classes.add(path.substring(0, path.length() - ".class".length()));
    }
  }

  private static byte[] classFile(String internalName) throws IOException {
    try (InputStream in =
        ProbeRecorderNotProbedTest.class
            .getClassLoader()
            .getResourceAsStream(internalName + ".class")) {
      assertNotNull(in, internalName);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }
}
