package datadog.trace.bootstrap.instrumentation.api;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What an HTTP client decorator pays to get the URL parts it tags from a {@link URL}, comparing
 * conversions to {@link URI} against reading the {@code URL} directly.
 *
 * <p>Every arm extracts the same values {@code HttpClientDecorator.onRequest} reads: scheme, host,
 * port, and the decoded path, query and fragment. {@code URI}'s getters decode percent-escapes and
 * {@code URL}'s do not, so the {@code URL} arm decodes them itself.
 *
 * <ul>
 *   <li>{@link #strict}: {@code url.toURI()}, as on master; a bad URL throws and is caught.
 *   <li>{@link #latched}: {@link URIUtils#toURI} through its shared {@link URIUtils.ToURI} latch,
 *       as in production. Well-formed input keeps it disengaged; bad input keeps it on the repair
 *       path.
 *   <li>{@link #repairPath}: the latch's repairing conversion on every call, which is what a
 *       well-formed URL costs while the latch is engaged.
 *   <li>{@link #urlFields}: no {@code URI} at all; the parts come from the {@code URL}, the
 *       structural alternative of a client-side {@code URIDataAdapter}.
 * </ul>
 *
 * <p>{@code depth} runs each call under that many extra frames, because a throw's cost grows with
 * the stack it captures and a real HTTP client call is deep. Only {@link #strict} on bad input
 * throws; the frames cost the other arms the same small constant.
 *
 * <p>The latch is shared across all threads, as the production {@code static final} is, so its
 * plain counter is written concurrently on the repair path. Run with {@code -prof gc}, and read the
 * allocation modes across forks rather than their mean.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class URIUtilsToURIBenchmark {

  @Param({"0", "50"})
  int depth;

  /** Shared like the production latch, which is a {@code static final}. */
  URIUtils.ToURI latch;

  /** Per thread, so each thread reads its own {@code URL}. */
  @State(Scope.Thread)
  public static class Url {
    @Param({"wellFormed", "bad"})
    String input;

    URL url;

    @Setup(Level.Trial)
    @SuppressWarnings("deprecation") // new URL(String) is what applications use to build these
    public void setup() throws MalformedURLException {
      // same length and shape; the bad one has the unencoded space URI rejects and URL accepts
      url =
          new URL(
              "wellFormed".equals(input)
                  ? "http://example.com/api/v1/search?q=hello%20world&page=2#top"
                  : "http://example.com/api/v1/search?q=hello world&page=2#top");
    }
  }

  @Setup(Level.Trial)
  public void setup() {
    latch = new URIUtils.ToURI();
  }

  @Benchmark
  public void strict(Url state, Blackhole bh) {
    consume(bh, strict(state.url, depth));
  }

  @Benchmark
  public void latched(Url state, Blackhole bh) {
    consume(bh, latched(latch, state.url, depth));
  }

  @Benchmark
  public void repairPath(Url state, Blackhole bh) {
    consume(bh, repairPath(latch, state.url, depth));
  }

  @Benchmark
  public void urlFields(Url state, Blackhole bh) {
    urlFields(bh, state.url, depth);
  }

  private static URI strict(URL url, int depth) {
    if (depth > 0) {
      return strict(url, depth - 1);
    }
    try {
      return url.toURI();
    } catch (URISyntaxException e) {
      return null; // HttpClientDecorator logs at debug and tags nothing
    }
  }

  private static URI latched(URIUtils.ToURI latch, URL url, int depth) {
    if (depth > 0) {
      return latched(latch, url, depth - 1);
    }
    final URI uri = latch.tryApply(url);
    if (uri != null) {
      return uri;
    }
    try {
      return url.toURI(); // what URIUtils.toURI does for an unrepairable URL
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static URI repairPath(URIUtils.ToURI latch, URL url, int depth) {
    if (depth > 0) {
      return repairPath(latch, url, depth - 1);
    }
    return latch.applySafely(url);
  }

  private static void urlFields(Blackhole bh, URL url, int depth) {
    if (depth > 0) {
      urlFields(bh, url, depth - 1);
      return;
    }
    bh.consume(url.getProtocol());
    bh.consume(url.getHost());
    bh.consume(url.getPort());
    bh.consume(URIUtils.decode(url.getPath()));
    bh.consume(URIUtils.decode(url.getQuery()));
    bh.consume(URIUtils.decode(url.getRef()));
  }

  private static void consume(Blackhole bh, URI uri) {
    if (uri == null) {
      bh.consume((Object) null);
      return;
    }
    bh.consume(uri.getScheme());
    bh.consume(uri.getHost());
    bh.consume(uri.getPort());
    bh.consume(uri.getPath());
    bh.consume(uri.getQuery());
    bh.consume(uri.getFragment());
  }
}
