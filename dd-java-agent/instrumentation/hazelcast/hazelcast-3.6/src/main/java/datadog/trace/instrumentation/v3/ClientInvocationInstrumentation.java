package datadog.trace.instrumentation.v3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.v3.DistributedObjectDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.ClientInvocation;
import com.hazelcast.client.spi.impl.ClientNonSmartInvocationServiceImpl;
import com.hazelcast.spi.discovery.DiscoveryStrategy;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ClientInvocationInstrumentation extends Instrumenter.Tracing {

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
      packageName + ".DistributedObjectDecorator",
      packageName + ".DistributedObjectDecorator$1",
      "datadog.trace.instrumentation.hazelcast.HazelcastConstants"
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.hazelcast.client.spi.impl.ClientInvocation");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isConstructor()
            .and(takesArgument(0, named("com.hazelcast.client.impl.HazelcastClientInstanceImpl"))),
        getClass().getName() + "$ConstructAdvice");
  }


  public static class ConstructAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void constructorExit(
        @Advice.This ClientInvocation that,
        @Advice.Argument(0) final HazelcastClientInstanceImpl hazelcastInstance) {

        DECORATE.onHazelcastInstance(activeSpan(), hazelcastInstance);
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
