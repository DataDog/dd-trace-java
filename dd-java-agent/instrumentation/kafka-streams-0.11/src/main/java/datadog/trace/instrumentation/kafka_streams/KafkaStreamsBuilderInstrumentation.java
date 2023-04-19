package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.streams.Topology;


@AutoService(Instrumenter.class)
public class KafkaStreamsBuilderInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaStreamsBuilderInstrumentation() {
    super("kafka", "kafka-streams");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.streams.StreamsBuilder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".TopologyContext"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isMethod().and(named("build")).and(takesNoArguments()),
        KafkaStreamsBuilderInstrumentation.class.getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Return final Topology topology
    ) {
      TopologyContext.Add(topology);
    }
  }
}