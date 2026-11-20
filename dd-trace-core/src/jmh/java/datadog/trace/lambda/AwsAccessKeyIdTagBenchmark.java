package datadog.trace.lambda;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.bootstrap.instrumentation.api.TagContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the per-invocation cost of the AWS access key ID tag that {@link LambdaAppSecHandler}
 * adds to the context seeding the invocation span: feature off against feature on, after the first
 * invocation has cached the value.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
public class AwsAccessKeyIdTagBenchmark {

  @Setup
  public void primeCache() {
    LambdaAwsAccessKeyId.reset();
    LambdaAwsAccessKeyId.get(name ->
        LambdaAwsAccessKeyId.ENV_ACCESS_KEY_ID.equals(name) ? "ASIAEXAMPLE000000001" : null);
  }

  @Benchmark
  @Fork(value = 1)
  public TagContext featureOff() {
    TagContext ctx = new TagContext();
    LambdaAppSecHandler.applyAwsAccessKeyIdTag(ctx);
    return ctx;
  }

  @Benchmark
  @Fork(value = 1, jvmArgsAppend = "-Ddd.appsec.aws.access.key.id.tag.enabled=true")
  public TagContext featureOn() {
    TagContext ctx = new TagContext();
    LambdaAppSecHandler.applyAwsAccessKeyIdTag(ctx);
    return ctx;
  }
}
