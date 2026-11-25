package datadog.trace.bootstrap.jit;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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

/**
 * Each fused sequence as a pair: the original code, and the helper that replaces it (warm, so
 * cached where the helper caches). Inputs are short, typical config/header-sized values.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(3)
public class FusedRewritesBenchmark {
  String token = "abc123";
  String csv = "alpha , beta,gamma ,  delta, epsilon";
  String colons = "alpha::beta::gamma::delta";
  String plainQuery = "alpha-beta_gamma.delta*42";
  String query = "alpha beta&gamma=delta/42";
  String encodedQuery = "alpha+beta%26gamma%3Ddelta%2F42";
  LocalDate date = LocalDate.of(2024, 2, 29);
  LocalDateTime dateTime = LocalDateTime.of(2024, 2, 29, 13, 5, 9);
  DecimalFormat decimalPrototype;

  @Setup
  public void setUp() {
    decimalPrototype = new DecimalFormat("#,##0.00");
  }

  @Benchmark
  public boolean compileMatches_jdk() {
    return Pattern.compile("[a-z]+\\d+").matcher(token).matches();
  }

  @Benchmark
  public boolean compileMatches_helper() {
    return FusedRewrites.compileMatches("[a-z]+\\d+", token);
  }

  @Benchmark
  public boolean compileFind_jdk() {
    return Pattern.compile("\\d+").matcher(token).find();
  }

  @Benchmark
  public boolean compileFind_helper() {
    return FusedRewrites.compileFind("\\d+", token);
  }

  @Benchmark
  public boolean compileLookingAt_jdk() {
    return Pattern.compile("[a-z]+").matcher(token).lookingAt();
  }

  @Benchmark
  public boolean compileLookingAt_helper() {
    return FusedRewrites.compileLookingAt("[a-z]+", token);
  }

  @Benchmark
  public String[] compileSplit_jdk() {
    return Pattern.compile("\\s*,\\s*").split(csv);
  }

  @Benchmark
  public String[] compileSplit_helper() {
    return FusedRewrites.compileSplit("\\s*,\\s*", csv);
  }

  @Benchmark
  public String temporalFormat_jdk() {
    return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  @Benchmark
  public String temporalFormat_helper() {
    return FusedRewrites.temporalFormat(dateTime, "yyyy-MM-dd HH:mm:ss");
  }

  @Benchmark
  public String formatterFormat_jdk() {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd").format(date);
  }

  @Benchmark
  public String formatterFormat_helper() {
    return FusedRewrites.formatterFormat("yyyy-MM-dd", date);
  }

  @Benchmark
  public DecimalFormat newDecimalFormat_jdk() {
    return new DecimalFormat("#,##0.00");
  }

  @Benchmark
  public DecimalFormat newDecimalFormat_helper() {
    return FusedRewrites.newDecimalFormat("#,##0.00");
  }

  /** The cost of a bare prototype copy, for comparison on JDKs where the helper doesn't copy. */
  @Benchmark
  public DecimalFormat newDecimalFormat_cloneOnly() {
    return (DecimalFormat) decimalPrototype.clone();
  }

  /** A copy given fresh symbols: a candidate (unverified) for an exact JDK 9+ path; not used. */
  @Benchmark
  public DecimalFormat newDecimalFormat_cloneWithFreshSymbols() {
    DecimalFormat copy = (DecimalFormat) decimalPrototype.clone();
    copy.setDecimalFormatSymbols(
        DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT)));
    return copy;
  }

  @Benchmark
  public String urlEncode_unchanged_jdk() throws UnsupportedEncodingException {
    return URLEncoder.encode(plainQuery, "UTF-8");
  }

  @Benchmark
  public String urlEncode_unchanged_helper() throws UnsupportedEncodingException {
    return FusedRewrites.urlEncode(plainQuery, "UTF-8");
  }

  @Benchmark
  public String urlEncode_changed_jdk() throws UnsupportedEncodingException {
    return URLEncoder.encode(query, "UTF-8");
  }

  @Benchmark
  public String urlEncode_changed_helper() throws UnsupportedEncodingException {
    return FusedRewrites.urlEncode(query, "UTF-8");
  }

  @Benchmark
  public String urlDecode_unchanged_jdk() throws UnsupportedEncodingException {
    return URLDecoder.decode(plainQuery, "UTF-8");
  }

  @Benchmark
  public String urlDecode_unchanged_helper() throws UnsupportedEncodingException {
    return FusedRewrites.urlDecode(plainQuery, "UTF-8");
  }

  @Benchmark
  public String urlDecode_changed_jdk() throws UnsupportedEncodingException {
    return URLDecoder.decode(encodedQuery, "UTF-8");
  }

  @Benchmark
  public String urlDecode_changed_helper() throws UnsupportedEncodingException {
    return FusedRewrites.urlDecode(encodedQuery, "UTF-8");
  }

  @Benchmark
  public String replace_jdk() {
    return colons.replace("::", "/");
  }

  @Benchmark
  public String replace_helper() {
    return StringRewrites.replace(colons, "::", "/");
  }

  /** {@code MessageDigest.getInstance(alg)} against copying a prototype; no helper yet. */
  @State(Scope.Benchmark)
  public static class Digest {
    @Param({"SHA-256", "SHA-1", "MD5"})
    String algorithm;

    MessageDigest prototype;

    @Setup
    public void setUp() throws NoSuchAlgorithmException {
      prototype = MessageDigest.getInstance(algorithm);
    }
  }

  @Benchmark
  public MessageDigest messageDigest_getInstance(Digest digest) throws NoSuchAlgorithmException {
    return MessageDigest.getInstance(digest.algorithm);
  }

  @Benchmark
  public MessageDigest messageDigest_clone(Digest digest) throws CloneNotSupportedException {
    return (MessageDigest) digest.prototype.clone();
  }
}
