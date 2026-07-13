package datadog.trace.bootstrap.instrumentation.buffer;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.io.Writer;
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
 * Char-path counterpart of {@link InjectingPipeOutputStreamBenchmark}: literal marker matcher vs.
 * structure-aware HTML parser vs. no-pipe baseline, across representative 64 KiB document shapes.
 * The sink folds every char into a checksum that each benchmark returns, so the output is really
 * consumed and the pipe overhead is the delta over withoutPipe.
 *
 * Sample run (Apple Silicon, JDK 25, 3x3s warmup / 5x3s measurement):
 *
 * Benchmark          (shape)  Mode  Cnt   Score  Units
 * withoutPipe         SIMPLE  avgt    5  16.155  us/op
 * withLiteralPipe     SIMPLE  avgt    5  16.607  us/op
 * withHtmlPipe        SIMPLE  avgt    5  17.000  us/op
 * withoutPipe       COMMENTS  avgt    5  16.469  us/op
 * withLiteralPipe   COMMENTS  avgt    5  16.512  us/op
 * withHtmlPipe      COMMENTS  avgt    5  16.773  us/op
 * withoutPipe        SCRIPTS  avgt    5  16.554  us/op
 * withLiteralPipe    SCRIPTS  avgt    5  16.568  us/op
 * withHtmlPipe       SCRIPTS  avgt    5  16.909  us/op
 * withoutPipe     PLAIN_TEXT  avgt    5  16.418  us/op
 * withLiteralPipe PLAIN_TEXT  avgt    5  37.167  us/op
 * withHtmlPipe    PLAIN_TEXT  avgt    5  26.778  us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class InjectingPipeWriterBenchmark {

  /** Consumes every char into a checksum so downstream writes cannot be optimized away. */
  static final class ChecksumWriter extends Writer {
    long checksum;

    @Override
    public void write(int c) {
      checksum += c;
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
      long c = checksum;
      for (int i = 0; i < len; i++) {
        c += cbuf[off + i];
      }
      checksum = c;
    }

    @Override
    public void write(String str, int off, int len) {
      long c = checksum;
      for (int i = 0; i < len; i++) {
        c += str.charAt(off + i);
      }
      checksum = c;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static final char[] MARKER = "</head>".toCharArray();
  private static final char[] CONTENT = "<script>/* rum */</script>".toCharArray();

  @Param({"SIMPLE", "COMMENTS", "SCRIPTS", "PLAIN_TEXT"})
  String shape;

  private char[] html;
  private final ChecksumWriter sink = new ChecksumWriter();

  @Setup
  public void setup() {
    html = HtmlDocuments.build(shape).toCharArray();
  }

  @Benchmark
  public long withoutPipe() throws IOException {
    sink.write(html, 0, html.length);
    return sink.checksum;
  }

  @Benchmark
  public long withLiteralPipe() throws IOException {
    try (InjectingPipeWriter out = new InjectingPipeWriter(sink, MARKER, CONTENT)) {
      out.write(html, 0, html.length);
    }
    return sink.checksum;
  }

  @Benchmark
  public long withHtmlPipe() throws IOException {
    try (InjectingPipeWriter out =
        new InjectingPipeWriter(sink, CONTENT, new HtmlCharMatcher(), null, null, null)) {
      out.write(html, 0, html.length);
    }
    return sink.checksum;
  }
}
