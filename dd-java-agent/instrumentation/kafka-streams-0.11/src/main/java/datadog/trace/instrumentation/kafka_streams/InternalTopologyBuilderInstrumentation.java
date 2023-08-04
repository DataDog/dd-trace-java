package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.kafka_clients.DataStreamsIgnoreContext;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;

@AutoService(Instrumenter.class)
public class InternalTopologyBuilderInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public InternalTopologyBuilderInstrumentation() {
    super("kafka", "kafka-streams");
  }

  @Override
  public String instrumentedType() {
    // this class exists only in kstreams 1.0.0+
    return "org.apache.kafka.streams.processor.internals.InternalTopologyBuilder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".GlobalTopologyContext",
        "datadog.trace.instrumentation.kafka_clients.DataStreamsIgnoreContext"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isMethod().and(named("build")).and(isPrivate()).and(takesArguments(1)),
        InternalTopologyBuilderInstrumentation.class.getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Return final ProcessorTopology topology
    ) {
      GlobalTopologyContext.registerTopology(topology);
      System.out.println("#### -> Registered topology");
      for (String topic: GlobalTopologyContext.getInternalTopics()) {
        System.out.println("#### -> Excluded topics extended with " + topic);
        DataStreamsIgnoreContext.add(topic);
      }
    }
  }
}
