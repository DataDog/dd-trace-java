package datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.xerial.snappy.Snappy;

public class NativeLibrariesApplication {

  private static final String TEXT =
      "It was a bright cold day in April, and the clocks were striking thirteen. Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, slipped quickly through the glass doors of Victory Mansions, though not quickly enough to prevent a swirl of gritty dust from entering along with him.";

  public static void main(String... args) throws Throwable {
    NativeLibrariesApplication application = new NativeLibrariesApplication();
    switch (args[0]) {
      case "lz4":
        application.lz4();
        break;
      case "snappy":
        application.snappy();
        break;
      default:
    }
  }

  private final Tracer tracer;

  public NativeLibrariesApplication() {
    this(GlobalTracer.get());
  }

  public NativeLibrariesApplication(Tracer tracer) {
    this.tracer = tracer;
  }

  public void lz4() {
    byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
    Span lz4 = tracer.buildSpan("lz4").start();
    try (Scope outer = tracer.activateSpan(lz4)) {
      for (int i = 0; i < 200; i++) {
        {
          Span compress = tracer.buildSpan("lz4.compress.fast").start();
          byte[] compressed;
          try (Scope inner = tracer.activateSpan(compress)) {
            compressed = LZ4Factory.nativeInstance().fastCompressor().compress(bytes);
          }
          compress.finish();
          Span decompress = tracer.buildSpan("lz4.decompress.fast").start();
          try (Scope inner = tracer.activateSpan(decompress)) {
            LZ4Factory.nativeInstance().safeDecompressor().decompress(compressed, bytes.length);
          }
          decompress.finish();
        }
        {
          Span compress = tracer.buildSpan("lz4.compress.high").start();
          byte[] compressed;
          try (Scope inner = tracer.activateSpan(compress)) {
            compressed = LZ4Factory.nativeInstance().highCompressor().compress(bytes);
          }
          compress.finish();
          Span decompress = tracer.buildSpan("lz4.decompress.high").start();
          try (Scope inner = tracer.activateSpan(decompress)) {
            LZ4Factory.nativeInstance().safeDecompressor().decompress(compressed, bytes.length);
          }
          decompress.finish();
        }
        {
          Span xxhash64 = tracer.buildSpan("xxhash64").start();
          try (Scope inner = tracer.activateSpan(xxhash64)) {
            long hash64 = XXHashFactory.nativeInstance().hash64().hash(bytes, 0, bytes.length, 0L);
          }
          xxhash64.finish();
          Span xxhash32 = tracer.buildSpan("xxhash32").start();
          try (Scope inner = tracer.activateSpan(xxhash32)) {
            int hash32 = XXHashFactory.nativeInstance().hash32().hash(bytes, 0, bytes.length, 0);
          }
          xxhash32.finish();
        }
      }
      lz4.finish();
    }
  }

  public void snappy() throws IOException {
    Span snappy = tracer.buildSpan("snappy").start();
    try (Scope outer = tracer.activateSpan(snappy)) {
      for (int i = 0; i < 100; i++) {
        Span compress = tracer.buildSpan("snappy.compress").start();
        byte[] compressed;
        try (Scope inner = tracer.activateSpan(compress)) {
          compressed = Snappy.compress(TEXT);
        }
        compress.finish();
        Span decompress = tracer.buildSpan("snappy.decompress").start();
        try (Scope inner = tracer.activateSpan(decompress)) {
          Snappy.uncompress(compressed);
        }
        decompress.finish();
      }
    }
    snappy.finish();
  }
}
