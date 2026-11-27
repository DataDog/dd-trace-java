package datadog.trace.core.tagprocessor;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.re2j.Pattern;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of obfuscating a span's query string with the default pattern, per span. Most queries carry
 * nothing the pattern redacts, so the misses are the common case:
 *
 * <ul>
 *   <li>{@code short}: a typical paging query.
 *   <li>{@code long}: a 300-odd character search query with many filters.
 *   <li>{@code near}: words that contain the pattern's literals ({@code passage}, {@code author},
 *       {@code designer}, {@code keyboard}) without matching it, so the literal check passes the
 *       query on to re2j: the worst case for the check, which adds its scan to the regex's.
 *   <li>{@code hit}: a token parameter, redacted.
 * </ul>
 *
 * <p>Each operation puts the query back first, as a new span would carry it. Run with {@code -prof
 * gc}.
 */
@State(Scope.Thread)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(4)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class QueryObfuscatorBenchmark {
  @Param({"short", "long", "near", "hit"})
  public String query;

  private QueryObfuscator obfuscator;
  private TagMap tags;
  private String text;

  @Setup
  public void setup() {
    obfuscator = new QueryObfuscator(null, false);
    tags = TagMap.create();
    switch (query) {
      case "short":
        text = "page=2&size=20";
        break;
      case "long":
        text =
            "q=running+shoes&category=footwear&brand=acme&brand=globex&color=red&color=blue"
                + "&size=10&size=10.5&width=wide&price_min=40&price_max=180&rating=4"
                + "&availability=in_stock&ship_to=US&sort=relevance&page=3&per_page=48"
                + "&utm_source=newsletter&utm_medium=email&utm_campaign=fall_sale"
                + "&ref=homepage_banner&session_hint=returning&locale=en_US&currency=USD";
        break;
      case "near":
        text = "path=/api/passage&tok=1&author_page=2&keyboard=us&eye=blue&designer=flat";
        break;
      case "hit":
        text = "page=2&access_token=abc123def456&size=20";
        break;
      default:
        throw new IllegalArgumentException(query);
    }
    final boolean redacted =
        Pattern.compile(QueryObfuscator.DEFAULT_OBFUSCATION_PATTERN).matcher(text).find();
    if (redacted != query.equals("hit")) {
      throw new IllegalStateException(query + (redacted ? " is" : " is not") + " redacted");
    }
  }

  @Benchmark
  public Object obfuscate() {
    tags.put(DDTags.HTTP_QUERY, text);
    obfuscator.processTags(tags, null, link -> {});
    return tags.getObject(DDTags.HTTP_QUERY);
  }
}
