package datadog.trace.instrumentation.kafka_streams10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;

@AutoService(InstrumenterModule.class)
public class InternalTopologyBuilderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public InternalTopologyBuilderInstrumentation() {
    super("kafka", "kafka-streams");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.streams.processor.internals.InternalTopologyBuilder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".StreamingContextUpdater",
      "datadog.trace.instrumentation.kafka_common.StreamingContext"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("build")).and(isPrivate()).and(takesArguments(1)),
        InternalTopologyBuilderInstrumentation.class.getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Return final ProcessorTopology topology) {
      StreamingContextUpdater.updateWithTopology(topology);
    }
  }
}
