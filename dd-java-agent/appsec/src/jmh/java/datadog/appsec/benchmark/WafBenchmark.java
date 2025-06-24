package datadog.appsec.benchmark;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.datadog.ddwaf.WafMetrics;
import com.datadog.ddwaf.exception.AbstractWafException;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okio.Okio;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 3, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3)
public class WafBenchmark {
  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  static {
    BenchmarkUtil.disableLogging();
    BenchmarkUtil.initializeWaf();
  }

  WafBuilder wafBuilder;
  WafHandle wafHandle;
  WafContext wafContext;
  Map<String, Object> wafData = new HashMap<>();
  Waf.Limits limits = new Waf.Limits(50, 500, 1000, 5000000, 5000000);

  @Benchmark
  public void withMetrics() throws Exception {
    WafMetrics metricsCollector = new WafMetrics();
    wafContext = new WafContext(wafHandle);
    try {
      wafContext.run(wafData, limits, metricsCollector);
    } finally {
      wafContext.close();
    }
  }

  @Benchmark
  public void withoutMetrics() throws Exception {
    wafContext = new WafContext(wafHandle);
    try {
      wafContext.run(wafData, limits, null);
    } finally {
      wafContext.close();
    }
  }

  @Setup(Level.Trial)
  public void setUp() throws AbstractWafException, IOException {
    wafBuilder = new WafBuilder();
    InputStream stream = getClass().getClassLoader().getResourceAsStream("test_multi_config.json");
    wafBuilder.addOrUpdateConfig("waf", ADAPTER.fromJson(Okio.buffer(Okio.source(stream))));
    wafHandle = wafBuilder.buildWafHandleInstance();
    wafData.put(KnownAddresses.REQUEST_METHOD.getKey(), "POST");
    wafData.put(
        KnownAddresses.REQUEST_URI_RAW.getKey(), "/foo/bar?foo=bar&foo=xpto&foo=%3cscript%3e");
    Map<String, String> headers = new HashMap<>();
    headers.put("host", "localhost:8080");
    headers.put("connection", "keep-alive");
    headers.put("accept-language", "pt,en-US;q=0.9,en;q=0.8");
    headers.put("cache-control", "max-age=0");
    headers.put("upgrade-insecure-requests", "1");
    headers.put(
        "accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8");
    headers.put("dnt", "1");
    headers.put("accept-encoding", "gzip, deflate, br");
    wafData.put(KnownAddresses.RESPONSE_HEADERS_NO_COOKIES.getKey(), headers);

    Map<String, List<String>> query = new HashMap<>();
    List<String> fooValues = new ArrayList<>();
    fooValues.add("bar");
    fooValues.add("xpto");
    fooValues.add("<script>");
    query.put("foo", fooValues);
    wafData.put(KnownAddresses.REQUEST_QUERY.getKey(), headers);

    Map<String, List<String>> cookies = new HashMap<>();
    cookies.put(
        "color_mode",
        Collections.singletonList(
            "=%7B%22color_mode%22%3A%22light%22%2C%22light_theme%22%3A%7B%22name%22%3A%22light%22%2C%22color_mode%22%3A%22light%22%7D%2C%22dark_theme%22%3A%7B%22name%22%3A%22dark%22%2C%22color_mode%22%3A%22dark%22%7D%7D"));
    cookies.put("tz", Collections.singletonList("Europe/Lisbon"));
    wafData.put(KnownAddresses.REQUEST_COOKIES.getKey(), cookies);
  }

  @TearDown(Level.Trial)
  public void teardown() {
    if (wafHandle != null && wafHandle.isOnline()) {
      wafHandle.close();
    }
    if (wafContext != null && wafContext.isOnline()) {
      wafContext.close();
    }
    if (wafBuilder != null && wafBuilder.isOnline()) {
      wafBuilder.close();
    }
  }
}
