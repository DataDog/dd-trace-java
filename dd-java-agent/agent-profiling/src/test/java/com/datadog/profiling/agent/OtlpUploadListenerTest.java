package com.datadog.profiling.agent;

import static com.datadog.profiling.agent.ProfilingAgent.withOtlpUpload;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtlpUploadListenerTest {

  private final List<String> calls = new ArrayList<>();
  private final TestRecordingData data = new TestRecordingData();

  @Test
  void classicUploadRunsBeforeOtlpAndRecordingOutlivesIt() {
    RecordingDataListener downstream =
        (type, d, sync) -> {
          calls.add("downstream");
          d.release(); // drops the base reference, as the classic uploader does
        };
    RecordingDataListener otlp =
        (type, d, sync) -> {
          calls.add("otlp:released=" + data.released);
          d.release();
        };

    withOtlpUpload(downstream, otlp).onNewData(RecordingType.CONTINUOUS, data, false);

    assertEquals(asList("downstream", "otlp:released=false"), calls);
    assertTrue(data.released);
  }

  @Test
  void otlpRunsWhenClassicUploadThrows() {
    RecordingDataListener downstream =
        (type, d, sync) -> {
          d.release();
          throw new IllegalArgumentException("boom");
        };
    RecordingDataListener otlp =
        (type, d, sync) -> {
          calls.add("otlp");
          d.release();
        };

    assertThrows(
        IllegalArgumentException.class,
        () -> withOtlpUpload(downstream, otlp).onNewData(RecordingType.CONTINUOUS, data, false));

    assertEquals(singletonList("otlp"), calls);
    assertTrue(data.released);
  }

  @Test
  void skipsOtlpWhenRecordingAlreadyReleased() {
    data.release();
    RecordingDataListener downstream = (type, d, sync) -> calls.add("downstream");
    RecordingDataListener otlp = (type, d, sync) -> calls.add("otlp");

    withOtlpUpload(downstream, otlp).onNewData(RecordingType.CONTINUOUS, data, false);

    assertEquals(singletonList("downstream"), calls);
  }

  private static final class TestRecordingData extends RecordingData {
    boolean released;

    TestRecordingData() {
      super(Instant.EPOCH, Instant.EPOCH, ProfilingSnapshot.Kind.PERIODIC);
    }

    @Override
    public RecordingInputStream getStream() {
      return new RecordingInputStream(new ByteArrayInputStream(new byte[0]));
    }

    @Override
    protected void doRelease() {
      released = true;
    }

    @Override
    public String getName() {
      return "test";
    }
  }
}
