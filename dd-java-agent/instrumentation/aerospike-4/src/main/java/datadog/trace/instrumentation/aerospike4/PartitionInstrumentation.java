package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.aerospike.client.cluster.Cluster;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.cluster.Partition;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class PartitionInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public PartitionInstrumentation() {
    super("aerospike");
  }

  @Override
  public String instrumentedType() {
    return "com.aerospike.client.cluster.Partition";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AerospikeClientDecorator",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(namedOneOf("getNodeRead", "getNodeWrite"))
            .and(takesArgument(0, named("com.aerospike.client.cluster.Cluster")))
            .and(returns(named("com.aerospike.client.cluster.Node"))),
        getClass().getName() + "$GetNodeAdvice");
  }

  public static final class GetNodeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void getNode(
        @Advice.Return final Node node,
        @Advice.Argument(0) final Cluster cluster,
        @Advice.This final Partition partition) {
      final AgentSpan span = activeSpan();
      // capture the connection details in the active Aerospike span
      if (span != null && DDSpanTypes.AEROSPIKE.equals(span.getSpanType())) {
        DECORATE.onConnection(span, node, cluster, partition);
      }
    }
  }
}
