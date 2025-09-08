package datadog.trace.instrumentation.hazelcast36;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.HAZELCAST_INSTANCE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.ClientNonSmartInvocationServiceImpl;
import com.hazelcast.spi.discovery.DiscoveryStrategy;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ClientInvocationInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ClientInvocationInstrumentation() {
    super("hazelcast_legacy");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HazelcastConstants",
      packageName + ".DistributedObjectDecorator",
      packageName + ".DistributedObjectDecorator$1"
    };
  }

  @Override
  public String instrumentedType() {
    return "com.hazelcast.client.spi.impl.ClientInvocation";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("com.hazelcast.client.impl.HazelcastClientInstanceImpl"))),
        getClass().getName() + "$ConstructAdvice");
  }

  public static class ConstructAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void constructorExit(
        @Advice.Argument(0) final HazelcastClientInstanceImpl hazelcastInstance) {

      final AgentSpan span = activeSpan();

      if (span != null
          && hazelcastInstance != null
          && hazelcastInstance.getLifecycleService() != null
          && hazelcastInstance.getLifecycleService().isRunning()) {

        activeSpan().setTag(HAZELCAST_INSTANCE, hazelcastInstance.getName());
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy,

        // New in 3.6
        DiscoveryStrategy strategy,

        // Renamed in 3.9
        ClientNonSmartInvocationServiceImpl invocationService) {
      strategy.start();
      proxy.getServiceName();
      invocationService.start();
    }
  }
}
