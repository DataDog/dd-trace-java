package datadog.trace.bootstrap.instrumentation.api;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class URIUtilsDecode {

  @Param({"/some/relatively/long/unencoded/path", "/n%C3%A5gon/relativt/l%C3%A5ng/kodad/v%C3%A4g"})
  String encoded;

  @Benchmark
  public void decode(Blackhole blackhole) {
    blackhole.consume(URIUtils.decode(encoded));
  }
}
