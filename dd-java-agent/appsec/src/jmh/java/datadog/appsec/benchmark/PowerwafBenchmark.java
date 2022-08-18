package datadog.appsec.benchmark;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.AppSecConfigDeserializer;
import com.datadog.appsec.event.data.KnownAddresses;
import io.sqreen.powerwaf.Additive;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.PowerwafMetrics;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public class PowerwafBenchmark {

  static {
    BenchmarkUtil.disableLogging();
    BenchmarkUtil.initializePowerwaf();
  }

  PowerwafContext ctx;
  Map<String, Object> wafData = new HashMap<>();
  Powerwaf.Limits limits = new Powerwaf.Limits(50, 500, 1000, 5000000, 5000000);

  @Benchmark
  public void withMetrics() throws Exception {
    PowerwafMetrics metricsCollector = ctx.createMetrics();
    Additive add = ctx.openAdditive();
    try {
      add.run(wafData, limits, metricsCollector);
    } finally {
      add.close();
    }
  }

  @Benchmark
  public void withoutMetrics() throws Exception {
    Additive add = ctx.openAdditive();
    try {
      add.run(wafData, limits, null);
    } finally {
      add.close();
    }
  }

  @Setup(Level.Trial)
  public void setUp() throws AbstractPowerwafException, IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream("test_multi_config.json");
    Map<String, AppSecConfig> cfg =
        Collections.singletonMap("waf", AppSecConfigDeserializer.INSTANCE.deserialize(stream));
    AppSecConfig waf = cfg.get("waf");
    ctx = Powerwaf.createContext("waf", waf.getRawConfig());

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
    ctx.delReference();
  }
}
