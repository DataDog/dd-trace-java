package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.core.CoreSpan;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositePayloadDispatcherTest {

  @Mock PayloadDispatcher dispatcherA;
  @Mock PayloadDispatcher dispatcherB;

  @Test
  void testOnDroppedTrace() {
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);
    int droppedSpansCount = 1234;

    dispatcher.onDroppedTrace(droppedSpansCount);

    verify(dispatcherA).onDroppedTrace(droppedSpansCount);
    verify(dispatcherB).onDroppedTrace(droppedSpansCount);
    verifyNoMoreInteractions(dispatcherA, dispatcherB);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testAddTrace() {
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);
    List<CoreSpan<?>> trace = Collections.singletonList(mock(CoreSpan.class));

    dispatcher.addTrace(trace);

    verify(dispatcherA).addTrace(trace);
    verify(dispatcherB).addTrace(trace);
    verifyNoMoreInteractions(dispatcherA, dispatcherB);
  }

  @Test
  void testFlush() {
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);

    dispatcher.flush();

    verify(dispatcherA).flush();
    verify(dispatcherB).flush();
    verifyNoMoreInteractions(dispatcherA, dispatcherB);
  }

  @Test
  void testGetApis() {
    CompositePayloadDispatcher dispatcher =
        new CompositePayloadDispatcher(dispatcherA, dispatcherB);
    RemoteApi apiA = mock(RemoteApi.class);
    RemoteApi apiB = mock(RemoteApi.class);
    when(dispatcherA.getApis()).thenReturn(Collections.singletonList(apiA));
    when(dispatcherB.getApis()).thenReturn(Collections.singletonList(apiB));

    Collection<RemoteApi> apis = dispatcher.getApis();

    assertEquals(Arrays.asList(apiA, apiB), apis);
  }
}
