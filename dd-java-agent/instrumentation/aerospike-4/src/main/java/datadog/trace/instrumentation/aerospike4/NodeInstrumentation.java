package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.aerospike.client.cluster.Node;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class NodeInstrumentation extends Instrumenter.Default {
  public NodeInstrumentation() {
    super("aerospike");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.aerospike.client.cluster.Node");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AerospikeClientDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(named("getConnection")), getClass().getName() + "$ConnectionAdvice");
    transformers.put(
        isMethod().and(named("getAsyncConnection")), getClass().getName() + "$ConnectionAdvice");
    return transformers;
  }

  public static final class ConnectionAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void getConnection(@Advice.This final Node node) {
      final AgentSpan span = activeSpan();
      // capture the assigned connection in the active Aerospike span
      if (span != null && DDSpanTypes.AEROSPIKE.equals(span.getSpanType())) {
        DECORATE.onConnection(span, node);
      }
    }
  }
}
