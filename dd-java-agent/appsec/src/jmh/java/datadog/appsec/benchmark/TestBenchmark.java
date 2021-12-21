// package datadog.appsec.benchmark;
//
// import org.openjdk.jmh.annotations.*;
//
// import java.net.URISyntaxException;
// import java.nio.charset.Charset;
// import java.util.*;
//
// import static java.util.concurrent.TimeUnit.MICROSECONDS;
// import static java.util.concurrent.TimeUnit.SECONDS;
//
// @BenchmarkMode(Mode.AverageTime)
// @Warmup(iterations = 5, time = 1, timeUnit = SECONDS)
// @Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
// @Fork(value = 1, warmups = 0)
// @OutputTimeUnit(MICROSECONDS)
// @State(Scope.Thread)
// public class TestBenchmark {
//
//  public static final List<String> HEADERS_ALLOW_LIST = new ArrayList<>();
//
//  public static final List<Integer> HEADERS_ALLOW_LIST_HASH = new ArrayList<>();
//
//  public static final Set<String> HEADERS_ALLOW_LIST_TREE = new TreeSet<>();
//
//  public static final Set<Integer> HEADERS_ALLOW_LIST_TREE_HASH = new TreeSet<>();
//
//
//  @Setup(Level.Trial)
//  public void setUp() {
//
//    /*HEADERS_ALLOW_LIST.addAll(Arrays.asList(
//        "x-forwarded-for",
//        "x-client-ip",
//        "x-real-ip",
//        "x-forwarded",
//        "x-cluster-client-ip",
//        "forwarded-for",
//        "forwarded",
//        "via",
//        "true-client-ip",
//        "content-length",
//        "content-type",
//        "content-encoding",
//        "content-language",
//        "host",
//        "user-agent",
//        "accept",
//        "accept-encoding",
//        "accept-language"));*/
//
//    for (int i=0; i<1000; i++) {
//      byte[] array = new byte[7]; // length is bounded by 7
//      new Random().nextBytes(array);
//      String generatedString = new String(array, Charset.forName("UTF-8"));
//      HEADERS_ALLOW_LIST.add(generatedString);
//      HEADERS_ALLOW_LIST_HASH.add(generatedString.hashCode());
//      HEADERS_ALLOW_LIST_TREE.add(generatedString);
//      HEADERS_ALLOW_LIST_TREE_HASH.add(generatedString.hashCode());
//    }
//    HEADERS_ALLOW_LIST.add("test");
//    HEADERS_ALLOW_LIST_HASH.add("test".hashCode());
//    HEADERS_ALLOW_LIST_TREE.add("test");
//    HEADERS_ALLOW_LIST_TREE_HASH.add("test".hashCode());
//  }
//
//
//  @Benchmark
//  public boolean test1() {
//    return HEADERS_ALLOW_LIST.contains("test");
//  }
//
//  @Benchmark
//  public boolean test2() {
//    for (String h : HEADERS_ALLOW_LIST) {
//      if (h.equals("test")) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//  @Benchmark
//  public boolean test3() {
//    for (String h : HEADERS_ALLOW_LIST) {
//      if (h.equalsIgnoreCase("test")) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//
//
//
//
//  @Benchmark
//  public boolean test1h() {
//    int hash = "test".hashCode();
//    return HEADERS_ALLOW_LIST_HASH.contains(hash);
//  }
//
//  @Benchmark
//  public boolean test2h() {
//    int hash = "test".hashCode();
//    for (Integer h : HEADERS_ALLOW_LIST_HASH) {
//      if (h == hash) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//  @Benchmark
//  public boolean test3h() {
//    for (Integer h : HEADERS_ALLOW_LIST_HASH) {
//      if (h.equals(hashCode())) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//
//
//
//  @Benchmark
//  public boolean test1t() {
//    return HEADERS_ALLOW_LIST_TREE.contains("test");
//  }
//
//  @Benchmark
//  public boolean test2t() {
//    for (String h : HEADERS_ALLOW_LIST_TREE) {
//      if (h.equals("test")) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//  @Benchmark
//  public boolean test3t() {
//    for (String h : HEADERS_ALLOW_LIST_TREE) {
//      if (h.equalsIgnoreCase("test")) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//
//
//
//  @Benchmark
//  public boolean test1th() {
//    int hash = "test".hashCode();
//    return HEADERS_ALLOW_LIST_TREE_HASH.contains(hash);
//  }
//
//  @Benchmark
//  public boolean test2th() {
//    int hash = "test".hashCode();
//    for (Integer h : HEADERS_ALLOW_LIST_TREE_HASH) {
//      if (h == hash) {
//        return true;
//      }
//    }
//    return false;
//  }
//
//  @Benchmark
//  public boolean test3th() {
//    for (Integer h : HEADERS_ALLOW_LIST_TREE_HASH) {
//      if (h.equals(hashCode())) {
//        return true;
//      }
//    }
//    return false;
//  }
//
// }
