package datadog.trace.instrumentation.v3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.ClientNonSmartInvocationServiceImpl;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.discovery.DiscoveryStrategy;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hazelcast.HazelcastConstants;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HazelcastInstanceInstrumentation extends Instrumenter.Tracing {

  public HazelcastInstanceInstrumentation() {
    super(HazelcastConstants.INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return HazelcastConstants.DEFAULT_ENABLED;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.hazelcast.HazelcastConstants"};
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.hazelcast.core.DistributedObject", "com.hazelcast.core.HazelcastInstance");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
        "com.hazelcast.client.impl.clientside.HazelcastClientProxy",
        "com.hazelcast.client.impl.HazelcastClientProxy");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "getAtomicLong",
                    "getAtomicReference",
                    "getCardinalityEstimator",
                    "getCountDownLatch",
                    "getCPSubsystem",
                    "getDistributedObject",
                    "getDistributedObjects",
                    "getDurableExecutorService",
                    "getExecutorService",
                    "getFlakeIdGenerator",
                    "getIdGenerator",
                    "getJobTracker",
                    "getLifecycleService",
                    "getList",
                    "getLocalEndpoint",
                    "getLock",
                    "getLoggingService",
                    "getMap",
                    "getMultiMap",
                    "getPartitionService",
                    "getPNCounter",
                    "getQueue",
                    "getQuorumService",
                    "getReliableTopic",
                    "getReplicatedMap",
                    "getRingbuffer",
                    "getScheduledExecutorService",
                    "getSemaphore",
                    "getSet",
                    "getTopic",
                    "getUserContext",
                    "getXAResource"))
            .and(returns(hasInterface(named("com.hazelcast.core.DistributedObject")))),
        getClass().getName() + "$InstanceAdvice");
  }

  public static class InstanceAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.This HazelcastInstance that,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final DistributedObject obj) {

      if (throwable == null) {
        InstrumentationContext.get(DistributedObject.class, HazelcastInstance.class).put(obj, that);
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
