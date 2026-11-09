package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.core.BlackholeBuffer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanHelper;
import datadog.trace.core.propagation.PropagationTags;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 120, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 120, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class TraceMapperBenchmark {

  @Param({"v04", "v04:x-dth", "v05", "v05:x-dth"})
  String mapperName;

  private TraceMapper mapper;
  private Writable writable;
  private List<DDSpan> trace;

  @Setup(Level.Trial)
  public void init(Blackhole blackhole) {
    PropagationTags propagationTags = null;
    String[] mapperAndFeatures = mapperName.split(":");
    switch (mapperAndFeatures[0]) {
      case "v04":
        mapper = new TraceMapperV0_4();
        break;
      case "v05":
        mapper = new TraceMapperV0_5();
        break;
      default:
        throw new IllegalArgumentException("Illegal mapper type " + mapperAndFeatures[0] + ".");
    }
    for (int i = 1; i < mapperAndFeatures.length; i++) {
      String feature = mapperAndFeatures[i];
      switch (feature) {
        case "x-dth":
          propagationTags =
              PropagationTags.factory()
                  .fromHeaderValue(
                      PropagationTags.HeaderType.DATADOG,
                      "_dd.p.anytag=value,_dd.p.dm=934086a686-4");
          break;
        default:
          throw new IllegalArgumentException("Unknown benchmark feature " + feature + ".");
      }
    }

    writable = new MsgPackWriter(new BlackholeBuffer(blackhole));
    trace = DDSpanHelper.singleRootTrace(propagationTags);
  }

  @Benchmark
  public void mapTrace() {
    mapper.map(trace, writable);
  }
}
