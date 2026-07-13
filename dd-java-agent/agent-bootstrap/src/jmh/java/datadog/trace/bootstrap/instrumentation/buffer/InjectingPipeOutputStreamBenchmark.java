package datadog.trace.bootstrap.instrumentation.buffer;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/*
 * Compares the RUM injection byte pipe using the literal marker matcher vs. the structure-aware
 * HTML parser, against a no-pipe baseline, across a few representative 64 KiB document shapes.
 *
 * The downstream sink folds every byte into a checksum that each benchmark returns, so the ~64 KiB
 * of output is really consumed (no dead-code elimination) — every arm pays the same downstream cost
 * and the pipe overhead shows up as the delta over withoutPipe. SIMPLE/COMMENTS/SCRIPTS inject
 * early in <head> then bulk-copy the body (overhead ≈ head scan); PLAIN_TEXT has no </head>, so the
 * whole document is scanned on top of the copy.
 *
 * Sample run (Apple Silicon, JDK 25, 3x3s warmup / 5x3s measurement). withoutPipe ~16 us/op is the
 * cost of consuming 64 KiB; the pipe overhead is the delta over it. On head-bearing pages injection
 * is a few percent; PLAIN_TEXT (no </head>) scans the whole doc, and there the HTML parser is
 * cheaper than the literal marker matcher.
 *
 * Benchmark          (shape)  Mode  Cnt   Score  Units
 * withoutPipe         SIMPLE  avgt    5  16.425  us/op
 * withLiteralPipe     SIMPLE  avgt    5  16.693  us/op
 * withHtmlPipe        SIMPLE  avgt    5  17.633  us/op
 * withoutPipe       COMMENTS  avgt    5  16.364  us/op
 * withLiteralPipe   COMMENTS  avgt    5  16.699  us/op
 * withHtmlPipe      COMMENTS  avgt    5  17.561  us/op
 * withoutPipe        SCRIPTS  avgt    5  16.273  us/op
 * withLiteralPipe    SCRIPTS  avgt    5  16.849  us/op
 * withHtmlPipe       SCRIPTS  avgt    5  17.197  us/op
 * withoutPipe     PLAIN_TEXT  avgt    5  16.114  us/op
 * withLiteralPipe PLAIN_TEXT  avgt    5  34.855  us/op
 * withHtmlPipe    PLAIN_TEXT  avgt    5  25.699  us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class InjectingPipeOutputStreamBenchmark {

  /** Consumes every byte into a checksum so downstream writes cannot be optimized away. */
  static final class ChecksumOutputStream extends OutputStream {
    long checksum;

    @Override
    public void write(int b) {
      checksum += b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
      long c = checksum;
      for (int i = 0; i < len; i++) {
        c += b[off + i];
      }
      checksum = c;
    }
  }

  private static final byte[] MARKER = "</head>".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CONTENT =
      "<script>/* rum */</script>".getBytes(StandardCharsets.UTF_8);

  @Param({"SIMPLE", "COMMENTS", "SCRIPTS", "PLAIN_TEXT"})
  String shape;

  private byte[] html;
  private final ChecksumOutputStream sink = new ChecksumOutputStream();

  @Setup
  public void setup() {
    html = HtmlDocuments.build(shape).getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public long withoutPipe() throws IOException {
    sink.write(html, 0, html.length);
    return sink.checksum;
  }

  @Benchmark
  public long withLiteralPipe() throws IOException {
    try (InjectingPipeOutputStream out = new InjectingPipeOutputStream(sink, MARKER, CONTENT)) {
      out.write(html, 0, html.length);
    }
    return sink.checksum;
  }

  @Benchmark
  public long withHtmlPipe() throws IOException {
    try (InjectingPipeOutputStream out =
        new InjectingPipeOutputStream(sink, CONTENT, new HtmlByteMatcher(), null, null, null)) {
      out.write(html, 0, html.length);
    }
    return sink.checksum;
  }
}
