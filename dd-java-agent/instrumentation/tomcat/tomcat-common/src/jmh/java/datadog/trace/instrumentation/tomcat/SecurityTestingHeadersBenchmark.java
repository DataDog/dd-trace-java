package datadog.trace.instrumentation.tomcat;

import static java.util.Arrays.fill;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.StringCache;
import org.apache.tomcat.util.http.MimeHeaders;
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
 * Tomcat 11.0.26, JMH 1.37, Java 17, arm64 macOS. Marker pass only, with a lightweight tag sink.
 *
 * <pre>
 * Headers / cache          Full scan ns/op  Lookup ns/op  Full scan B/op  Lookup B/op
 * Typical / disabled               505            20          3590             ~0
 * 4 KiB cookie / disabled          858            18         15494             ~0
 * Typical / warmed                 975            18           630             ~0
 * 4 KiB cookie / warmed           1242            18         12950             ~0
 * String-backed                     26            29            ~0             ~0
 * </pre>
 */
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Threads(1)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SecurityTestingHeadersBenchmark {
  static final String SCAN = "x-datadog-endpoint-scan";
  static final String TEST = "x-datadog-security-test";
  static final ExtractAdapter<MimeHeaders> ADAPTER =
      new ExtractAdapter<MimeHeaders>() {
        @Override
        MimeHeaders getMimeHeaders(MimeHeaders headers) {
          return headers;
        }
      };

  @Param({"bytesTypical", "bytesCookie4k", "stringsTypical", "bytesMarkers"})
  public String scenario;

  @Param({"false", "true"})
  public boolean stringCache;

  MimeHeaders[] requests;
  int cursor;
  final Tags tags = new Tags();

  @Setup
  public void setup() {
    StringCache cache = new StringCache();
    cache.setByteEnabled(stringCache);
    cache.reset();
    requests = new MimeHeaders[32];
    for (int i = 0; i < requests.length; i++) {
      MimeHeaders h = new MimeHeaders();
      boolean bytes = !scenario.startsWith("strings");
      String cookie =
          "session=" + i + "; prefs=" + repeat('a', scenario.equals("bytesCookie4k") ? 4070 : 100);
      String[][] fields = {
        {"host", "service.example.test"},
        {
          "user-agent",
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"
        },
        {"accept", "application/json"},
        {"accept-encoding", "gzip, deflate, br"},
        {"accept-language", "en-US,en;q=0.9"},
        {"connection", "keep-alive"},
        {"traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"},
        {"tracestate", "dd=s:1;o:rum"},
        {"authorization", "Bearer " + repeat('b', 180) + i},
        {"cookie", cookie},
        {"x-request-id", "request-" + i},
        {"content-type", "application/json"}
      };
      for (String[] f : fields) add(h, f[0], f[1], bytes);
      if (scenario.equals("bytesMarkers")) {
        add(h, "X-Datadog-Endpoint-Scan", "scan-" + i, bytes);
        add(h, TEST, "test-" + i, bytes);
      }
      // Model a preceding visit: conversion must not accidentally turn byte-backed headers into
      // strings.
      for (int k = 0; k < h.size(); k++) {
        ExtractAdapter.messageBytesToString(h.getName(k));
        ExtractAdapter.messageBytesToString(h.getValue(k));
      }
      if (bytes && h.getValue(9).getType() != MessageBytes.T_BYTES)
        throw new AssertionError("representation changed");
      requests[i] = h;
    }
    if (stringCache) {
      // Warm eligible values as preceding propagation/application header reads would.
      for (int pass = 0; pass < 1000; pass++) {
        for (MimeHeaders h : requests) {
          for (int k = 0; k < h.size(); k++) {
            ExtractAdapter.messageBytesToString(h.getName(k));
            ExtractAdapter.messageBytesToString(h.getValue(k));
          }
        }
      }
    }
    for (int i = 0; i < requests.length; i++) {
      cursor = i;
      Tags baseline = fullScan();
      String scan = baseline.scan;
      String test = baseline.test;
      cursor = i;
      Tags candidate = targetedLookup();
      if (!Objects.equals(scan, candidate.scan) || !Objects.equals(test, candidate.test)) {
        throw new IllegalStateException("Marker lookup differs from full scan");
      }
    }
    cursor = 0;
  }

  static String repeat(char c, int n) {
    char[] chars = new char[n];
    fill(chars, c);
    return new String(chars);
  }

  static void add(MimeHeaders h, String name, String value, boolean bytes) {
    MessageBytes v;
    if (bytes) {
      byte[] b = name.getBytes(StandardCharsets.ISO_8859_1);
      v = h.addValue(b, 0, b.length);
      if (value != null) {
        b = value.getBytes(StandardCharsets.ISO_8859_1);
        v.setBytes(b, 0, b.length);
      }
    } else {
      v = h.addValue(name);
      if (value != null) v.setString(value);
    }
  }

  MimeHeaders next() {
    return requests[cursor++ & 31];
  }

  @Benchmark
  public Tags fullScan() {
    tags.scan = tags.test = null;
    ADAPTER.forEachKey(next(), new Classifier(tags));
    return tags;
  }

  @Benchmark
  public Tags targetedLookup() {
    MimeHeaders h = next();
    tags.scan = firstValue(h, SCAN);
    tags.test = firstValue(h, TEST);
    return tags;
  }

  static String firstValue(MimeHeaders h, String name) {
    for (int i = h.findHeader(name, 0); i >= 0; i = h.findHeader(name, i + 1)) {
      String value = ExtractAdapter.messageBytesToString(h.getValue(i));
      if (value != null) return value;
    }
    return null;
  }

  public static final class Tags {
    String scan;
    String test;
  }

  static final class Classifier implements AgentPropagation.KeyClassifier {
    final Tags tags;
    boolean scanSeen, testSeen;

    Classifier(Tags tags) {
      this.tags = tags;
    }

    @Override
    public boolean accept(String key, String value) {
      if (key == null || value == null) return true;
      if (!scanSeen && SCAN.equalsIgnoreCase(key)) {
        tags.scan = value;
        scanSeen = true;
      } else if (!testSeen && TEST.equalsIgnoreCase(key)) {
        tags.test = value;
        testSeen = true;
      }
      return !(scanSeen && testSeen);
    }
  }
}
