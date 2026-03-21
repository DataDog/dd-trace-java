package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.core.CoreSpan;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositePayloadDispatcherTest {

  @Test
  void testOnDroppedTrace() {
    PayloadDispatcher dispatcherA = mock(PayloadDispatcher.class);
    PayloadDispatcher dispatcherB = mock(PayloadDispatcher.class);
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);

    int droppedSpansCount = 1234;

    dispatcher.onDroppedTrace(droppedSpansCount);

    verify(dispatcherA, times(1)).onDroppedTrace(droppedSpansCount);
    verify(dispatcherB, times(1)).onDroppedTrace(droppedSpansCount);
    verifyNoMoreInteractions(dispatcherA, dispatcherB);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testAddTrace() {
    PayloadDispatcher dispatcherA = mock(PayloadDispatcher.class);
    PayloadDispatcher dispatcherB = mock(PayloadDispatcher.class);
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);

    @SuppressWarnings("unchecked")
    List<CoreSpan<?>> trace = Collections.singletonList(mock(CoreSpan.class));

    dispatcher.addTrace(trace);

    verify(dispatcherA, times(1)).addTrace(trace);
    verify(dispatcherB, times(1)).addTrace(trace);
    verifyNoMoreInteractions(dispatcherA, dispatcherB);
  }

  @Test
  void testFlush() {
    PayloadDispatcher dispatcherA = mock(PayloadDispatcher.class);
    PayloadDispatcher dispatcherB = mock(PayloadDispatcher.class);
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);

    dispatcher.flush();

    verify(dispatcherA, times(1)).flush();
    verify(dispatcherB, times(1)).flush();
    verifyNoMoreInteractions(dispatcherA, dispatcherB);
  }

  @Test
  void testGetApis() {
    PayloadDispatcher dispatcherA = mock(PayloadDispatcher.class);
    PayloadDispatcher dispatcherB = mock(PayloadDispatcher.class);
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);

    DDIntakeApi intakeApi = mock(DDIntakeApi.class);
    DDAgentApi agentApi = mock(DDAgentApi.class);
    when(dispatcherA.getApis()).thenReturn(Collections.singletonList(intakeApi));
    when(dispatcherB.getApis()).thenReturn(Collections.singletonList(agentApi));

    List<?> apis = new java.util.ArrayList<>(dispatcher.getApis());

    assertEquals(Arrays.asList(intakeApi, agentApi), apis);
  }
}
