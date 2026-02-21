package datadog.trace.core.tagprocessor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import datadog.query.QueryObfuscator;

@Fork(2)
@Warmup(iterations=2)
@Measurement(iterations=3)
@Threads(8)
public class QueryObfuscatorBenchmark {
  static final QueryObfuscator obfuscator = new QueryObfuscator(null);
  
  static final String NO_REDACT = "foo=bar";
  static final String SIMPLE_REDACT = "app_key=1111";
  static final String LARGE_REDACT = repeat("app_key=1111&application_key=2222&token=0894-4832", '&', 4096);

  static final String repeat(String repeat, char separator, int length) {
    StringBuilder builder = new StringBuilder(length);
    builder.append(repeat);
    while ( builder.length() + repeat.length() + 1 < length ) {
      builder.append(separator).append(repeat);
    }
    return builder.toString();
  }
  
  @Benchmark
  public String noRedact() {
	return obfuscator.obfuscate(NO_REDACT);
  }
  
  @Benchmark
  public String simpleRedact() {
	return obfuscator.obfuscate(SIMPLE_REDACT);
  }
  
  @Benchmark
  public String largeRedact() {	
	return obfuscator.obfuscate(LARGE_REDACT);
  }
}
