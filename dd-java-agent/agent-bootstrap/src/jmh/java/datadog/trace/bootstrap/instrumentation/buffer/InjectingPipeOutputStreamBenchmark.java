package datadog.trace.bootstrap.instrumentation.buffer;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/*
 * Benchmark                                       Mode  Cnt   Score   Error  Units
 * InjectingPipeOutputStreamBenchmark.withPipe     avgt    2  15.515          us/op
 * InjectingPipeOutputStreamBenchmark.withoutPipe  avgt    2  12.861          us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 2, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class InjectingPipeOutputStreamBenchmark {
  private static final List<String> htmlContent;
  private static final byte[] marker;
  private static final byte[] content;

  static {
    try (InputStream is = new URL("https://www.google.com").openStream()) {
      htmlContent = IOUtils.readLines(is, StandardCharsets.UTF_8);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    marker = "</head>".getBytes(StandardCharsets.UTF_8);
    content = "<script/>".getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public void withPipe() throws Exception {
    try (final PrintWriter out =
        new PrintWriter(
            new InjectingPipeOutputStream(
                new ByteArrayOutputStream(), marker, content, null, null))) {
      htmlContent.forEach(out::println);
    }
  }

  @Benchmark
  public void withoutPipe() throws Exception {
    try (final PrintWriter out = new PrintWriter(new ByteArrayOutputStream())) {
      htmlContent.forEach(out::println);
    }
  }
}
