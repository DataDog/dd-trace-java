package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.core.DDSpan;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiWriterTest {

  @Test
  void testThatMultiWriterDelegatesToAll() throws Exception {
    Writer[] writers = new Writer[3];
    Writer mockW1 = mock(Writer.class);
    Writer mockW2 = mock(Writer.class);
    writers[0] = mockW1;
    // null in position 1 to check that we skip that
    writers[2] = mockW2;
    MultiWriter writer = new MultiWriter(writers);
    List<DDSpan> trace = new LinkedList<>();

    writer.start();
    verify(mockW1, times(1)).start();
    verify(mockW2, times(1)).start();
    verifyNoMoreInteractions(mockW1, mockW2);

    writer.write(trace);
    verify(mockW1, times(1)).write(trace);
    verify(mockW2, times(1)).write(trace);
    verifyNoMoreInteractions(mockW1, mockW2);

    when(mockW1.flush()).thenReturn(true);
    when(mockW2.flush()).thenReturn(true);
    boolean flushed = writer.flush();
    assertTrue(flushed);
    verify(mockW1, times(1)).flush();
    verify(mockW2, times(1)).flush();
    verifyNoMoreInteractions(mockW1, mockW2);

    when(mockW1.flush()).thenReturn(true);
    when(mockW2.flush()).thenReturn(false);
    boolean notFlushed = writer.flush();
    assertFalse(notFlushed);
    verify(mockW1, times(2)).flush();
    verify(mockW2, times(2)).flush();
    verifyNoMoreInteractions(mockW1, mockW2);

    writer.close();
    verify(mockW1, times(1)).close();
    verify(mockW2, times(1)).close();
    verifyNoMoreInteractions(mockW1, mockW2);

    writer.incrementDropCounts(0);
    verify(mockW1, times(1)).incrementDropCounts(0);
    verify(mockW2, times(1)).incrementDropCounts(0);
    verifyNoMoreInteractions(mockW1, mockW2);
  }
}
