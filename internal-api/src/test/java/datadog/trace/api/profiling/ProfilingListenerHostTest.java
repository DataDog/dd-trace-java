package datadog.trace.api.profiling;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class ProfilingListenerHostTest {
  private ProfilingListenerHost<ProfilingSnapshot> instance;

  @Before
  public void setup() {
    instance = new ProfilingListenerHost<>();
  }

  @Test
  public void verifyInteractions() {
    ProfilingListener<ProfilingSnapshot> listener = Mockito.mock(ProfilingListener.class);

    instance.addListener(listener);
    ProfilingSnapshot ps = Mockito.mock(ProfilingSnapshot.class);
    instance.fireOnData(ps);

    Mockito.verify(listener, Mockito.times(1)).onData(ps);

    Mockito.clearInvocations(listener);
    instance.removeListener(listener);

    instance.fireOnData(ps);
    Mockito.verify(listener, Mockito.never()).onData(ps);
  }
}
