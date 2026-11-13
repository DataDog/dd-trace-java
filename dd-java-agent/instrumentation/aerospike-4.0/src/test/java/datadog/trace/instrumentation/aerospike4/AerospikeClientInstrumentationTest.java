package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.instrumentation.aerospike4.AerospikeClientInstrumentation.WRAPPABLE_LISTENERS;
import static datadog.trace.instrumentation.aerospike4.AerospikeClientInstrumentation.asyncRequestMethods;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.async.EventLoop;
import com.aerospike.client.listener.RecordListener;
import com.aerospike.client.policy.Policy;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/** Runs against the oldest and the latest supported client, as test and latestDepTest. */
class AerospikeClientInstrumentationTest {

  private static final ElementMatcher<MethodDescription> ASYNC_REQUESTS = asyncRequestMethods();

  /**
   * The async advice replaces the listener argument with a TracingListener, which fails, after the
   * span is started and activated, unless a TracingListener can stand in for that listener type.
   */
  @Test
  void onlyReplacesListenersATracingListenerCanStandIn() {
    int matched = 0;
    for (Method method : AerospikeClient.class.getMethods()) {
      if (ASYNC_REQUESTS.matches(new MethodDescription.ForLoadedMethod(method))) {
        matched++;
        Class<?> listenerType = method.getParameterTypes()[1];
        assertTrue(
            listenerType.isAssignableFrom(TracingListener.class),
            () -> "TracingListener can't replace the " + listenerType.getName() + " of " + method);
      }
    }
    assertTrue(matched > 0, "no async request matched");
  }

  @Test
  void stillTracesAsyncGets() throws Exception {
    Method get =
        AerospikeClient.class.getMethod(
            "get", EventLoop.class, RecordListener.class, Policy.class, Key.class);

    assertTrue(ASYNC_REQUESTS.matches(new MethodDescription.ForLoadedMethod(get)));
  }

  @Test
  void wrappableListenersAreTheOnesTracingListenerImplements() {
    Set<String> implemented = new HashSet<>();
    for (Class<?> type : TracingListener.class.getInterfaces()) {
      implemented.add(type.getName());
    }

    assertEquals(implemented, new HashSet<>(asList(WRAPPABLE_LISTENERS)));
  }
}
