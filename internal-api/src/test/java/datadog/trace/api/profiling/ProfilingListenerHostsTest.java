package datadog.trace.api.profiling;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProfilingListenerHostsTest {
  public static class TypeA implements ObservableType {}

  public static class TypeB implements ObservableType {}

  @Test
  public void getHost() {
    ProfilingListenerHost<TypeA> hostA = ProfilingListenerHosts.getHost(TypeA.class);
    ProfilingListenerHost<TypeA> hostA1 = ProfilingListenerHosts.getHost(TypeA.class);
    ProfilingListenerHost<TypeB> hostB = ProfilingListenerHosts.getHost(TypeB.class);

    assertNotNull(hostA);
    assertNotNull(hostA1);
    assertNotNull(hostB);
    assertEquals(hostA, hostA1);
    assertNotEquals(hostA, hostB);
  }
}
