package com.datadog.profiling.agent;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FanOutRecordingDataListenerTest {

  @Test
  void retainsAnExtraReferenceAndInvokesBothListeners() {
    RecordingDataListener primary = mock(RecordingDataListener.class);
    RecordingDataListener secondary = mock(RecordingDataListener.class);
    RecordingData data = mock(RecordingData.class);

    RecordingDataListener listener = FanOutRecordingDataListener.wrap(primary, secondary);
    listener.onNewData(RecordingType.CONTINUOUS, data, true);

    // The secondary listener may release asynchronously, independently of the primary's base
    // reference, so it must be handed its own reference via retain().
    verify(data).retain();

    InOrder order = inOrder(secondary, primary);
    order.verify(secondary).onNewData(RecordingType.CONTINUOUS, data, true);
    order.verify(primary).onNewData(RecordingType.CONTINUOUS, data, true);
  }
}
