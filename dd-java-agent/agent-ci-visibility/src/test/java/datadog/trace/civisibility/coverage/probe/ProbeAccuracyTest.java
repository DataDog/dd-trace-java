package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.civisibility.coverage.probe.fixtures.CoverageFixture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs each fixture scenario twice, once under the line probes and once under {@link
 * ReferenceLines}, and expects the same lines and the same outcome.
 */
class ProbeAccuracyTest {

  private static final String FIXTURES = CoverageFixture.class.getPackage().getName() + ".";

  static IntStream scenarios() {
    return IntStream.range(0, CoverageFixture.SCENARIOS);
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void probesReportTheReferenceLines(int scenario) throws Exception {
    ReferenceLines.drain();
    FixtureLoader referenceLoader = new FixtureLoader(ReferenceLines::instrument);
    String referenceOutcome = run(referenceLoader, scenario);
    Map<String, BitSet> referenceLines = ReferenceLines.drain();

    FixtureLoader probeLoader = new FixtureLoader(null);
    String probeOutcome = run(probeLoader, scenario);
    Map<String, BitSet> probeLines = probeLines(probeLoader);

    assertFalse(referenceLines.isEmpty(), "the reference recorded no lines");
    assertEquals(referenceOutcome, probeOutcome);
    assertEquals(referenceLines, probeLines);
  }

  private static String run(ClassLoader loader, int scenario) throws Exception {
    Class<?> fixture = Class.forName(CoverageFixture.class.getName(), true, loader);
    try {
      return String.valueOf(fixture.getMethod("run", int.class).invoke(null, scenario));
    } catch (InvocationTargetException e) {
      return "threw " + e.getCause();
    }
  }

  /** Lines of the classes this loader defined: hits outside any window plus initializers. */
  private static Map<String, BitSet> probeLines(ClassLoader loader) {
    Map<String, BitSet> lines = new HashMap<>();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass == null || instrumentedClass.loader() != loader) {
        continue;
      }
      BitSet classLines = new BitSet();
      instrumentedClass.addUnownedLines(classLines);
      instrumentedClass.addInitLines(ProbeRecorder.PROBES[classId], classLines);
      if (!classLines.isEmpty()) {
        lines.computeIfAbsent(instrumentedClass.sourceFile(), file -> new BitSet()).or(classLines);
      }
    }
    return lines;
  }

  /** Defines the fixture classes, instrumented by probes or by the given instrumenter. */
  private static final class FixtureLoader extends ClassLoader {
    private final Function<byte[], byte[]> instrumenter;

    FixtureLoader(Function<byte[], byte[]> instrumenter) {
      super(ProbeAccuracyTest.class.getClassLoader());
      this.instrumenter = instrumenter;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith(FIXTURES)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        byte[] original = read(name);
        byte[] bytes =
            instrumenter != null
                ? instrumenter.apply(original)
                : ProbeClassTransformer.instrument(original, this, true);
        if (bytes == null) {
          bytes = original;
        }
        return defineClass(name, bytes, 0, bytes.length);
      }
    }

    private byte[] read(String name) throws ClassNotFoundException {
      try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) != -1; ) {
          out.write(buffer, 0, read);
        }
        return out.toByteArray();
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }
}
