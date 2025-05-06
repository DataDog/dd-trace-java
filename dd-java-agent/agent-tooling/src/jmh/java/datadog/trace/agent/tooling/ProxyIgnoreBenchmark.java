package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.matcher.ProxyIgnoredClassNameTrie;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/*
Benchmark                                                                                                                                  (className)   Mode  Cnt  Score   Error   Units
ProxyIgnoreBenchmark.useContains                                                                 org.springframework.util.ConcurrentReferenceHashMap$4  thrpt    2  0.007          ops/ns
ProxyIgnoreBenchmark.useContains                                                                    java.lang.invoke.LambdaForm$DMH/0x00007fe9f0388000  thrpt    2  0.008          ops/ns
ProxyIgnoreBenchmark.useContains  org.springframework.core.annotation.RepeatableContainers$StandardRepeatableContainers$$Lambda$315/0x00007fe9f03adc70  thrpt    2  0.003          ops/ns
ProxyIgnoreBenchmark.useContains                                             datadog.test.package.redis.RedisTemplateProvider$$TestCGLIB$$FastClass$$0  thrpt    2  0.025          ops/ns
ProxyIgnoreBenchmark.useTrie                                                                     org.springframework.util.ConcurrentReferenceHashMap$4  thrpt    2  0.026          ops/ns
ProxyIgnoreBenchmark.useTrie                                                                        java.lang.invoke.LambdaForm$DMH/0x00007fe9f0388000  thrpt    2  0.026          ops/ns
ProxyIgnoreBenchmark.useTrie      org.springframework.core.annotation.RepeatableContainers$StandardRepeatableContainers$$Lambda$315/0x00007fe9f03adc70  thrpt    2  0.009          ops/ns
ProxyIgnoreBenchmark.useTrie                                                 datadog.test.package.redis.RedisTemplateProvider$$TestCGLIB$$FastClass$$0  thrpt    2  0.029          ops/ns
*/
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Measurement(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Fork(value = 1)
public class ProxyIgnoreBenchmark {
  @Param({
    "org.springframework.util.ConcurrentReferenceHashMap$4",
    "java.lang.invoke.LambdaForm$DMH/0x00007fe9f0388000",
    "org.springframework.core.annotation.RepeatableContainers$StandardRepeatableContainers$$Lambda$315/0x00007fe9f03adc70",
    // worst case for trie based
    "datadog.test.package.redis.RedisTemplateProvider$$TestCGLIB$$FastClass$$0"
  })
  public String className;

  @Benchmark
  public void useContains(Blackhole bh) {
    if (className.indexOf('$') > -1) {
      if (className.contains("$JaxbAccessor")
          || className.contains("CGLIB$$")
          || className.contains("$__sisu")
          || className.contains("$$EnhancerByGuice$$")
          || className.contains("$$EnhancerByProxool$$")
          || className.contains("$$$view")
          || className.contains("$$$endpoint") // jboss mdb proxies
          || className.contains("$$_Weld")
          || className.contains("_$$_jvst")) {
        bh.consume(true);
      }
    }
  }

  @Benchmark
  public void useTrie(Blackhole bh) {
    int last = -1;
    int idx;
    while (true) {
      idx = className.indexOf('$', last + 1);
      if (idx < 0) {
        break;
      }
      if (last < 0 && className.contains("CGLIB$$")) {
        break;
      }
      if (idx == last + 1) {
        // skip the trie if consecutive $$ since, to be efficient, we can match prefixes from the
        // first dollar
        last = idx;
        continue;
      }
      last = idx;
      if (ProxyIgnoredClassNameTrie.apply(className, idx) >= 0) {
        return;
      }
    }
    bh.consume(true);
  }
}
