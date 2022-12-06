package com.datadog.profiling.auxiliary;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingInputStream;
import datadog.trace.api.profiling.ProfilingSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nonnull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

class AuxiliaryRecordingDataTest {
  private static final byte[] MAIN_DATA = new byte[] {1, 2, 3};
  private static final byte[] AUXILIARY_DATA = new byte[] {4, 5, 6};

  private static class TestRecordingData extends RecordingData {
    private final String name;
    private final byte[] testData;
    volatile boolean isReleased = false;

    public TestRecordingData(String name, Instant start, Instant end, Kind kind, byte[] testData) {
      super(start, end, kind);
      this.name = name;
      this.testData = testData;
    }

    @Nonnull
    @Override
    public RecordingInputStream getStream() throws IOException {
      return new RecordingInputStream(new ByteArrayInputStream(testData));
    }

    @Override
    public void release() {
      isReleased = true;
    }

    @Nonnull
    @Override
    public String getName() {
      return name;
    }
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  void testNullMaindata(ProfilingSnapshot.Kind kind) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuxiliaryRecordingData(Instant.now(), Instant.now(), kind, null));
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  void testMainDataThrowing(ProfilingSnapshot.Kind kind) throws IOException {
    RecordingData mainData = Mockito.mock(RecordingData.class);
    RecordingData auxiliaryData = Mockito.mock(RecordingData.class);

    Mockito.when(mainData.getKind()).thenReturn(kind);
    Mockito.when(auxiliaryData.getKind()).thenReturn(kind);

    byte[] auxDataRaw = new byte[10];
    Mockito.when(mainData.getStream()).thenThrow(new IOException());
    Mockito.when(auxiliaryData.getStream())
        .thenReturn(new RecordingInputStream(new ByteArrayInputStream(auxDataRaw)));

    AuxiliaryRecordingData combined =
        new AuxiliaryRecordingData(Instant.now(), Instant.now(), kind, mainData, auxiliaryData);

    assertEquals(0, readFromStream(combined.getStream()));
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  void testAuxiliaryDataThrowing(ProfilingSnapshot.Kind kind) throws IOException {
    RecordingData mainData = Mockito.mock(RecordingData.class);
    RecordingData auxiliaryData = Mockito.mock(RecordingData.class);

    Mockito.when(mainData.getKind()).thenReturn(kind);
    Mockito.when(auxiliaryData.getKind()).thenReturn(kind);

    byte[] mainDataRaw = new byte[10];
    Mockito.when(mainData.getStream())
        .thenReturn(new RecordingInputStream(new ByteArrayInputStream(mainDataRaw)));
    Mockito.when(auxiliaryData.getStream()).thenThrow(new IOException());

    AuxiliaryRecordingData combined =
        new AuxiliaryRecordingData(Instant.now(), Instant.now(), kind, mainData, auxiliaryData);

    assertEquals(mainDataRaw.length, readFromStream(combined.getStream()));
  }

  private int readFromStream(InputStream stream) throws IOException {
    byte[] buffer = new byte[128];
    int allBytes = 0;
    int read = 0;
    while ((read = stream.read(buffer)) > 0) {
      allBytes += read;
    }
    return allBytes;
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  void testMainDataOnly(ProfilingSnapshot.Kind kind) throws IOException {
    String mainName = "main";
    TestRecordingData mainData =
        new TestRecordingData(
            mainName, Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), kind, MAIN_DATA);
    AuxiliaryRecordingData data =
        new AuxiliaryRecordingData(
            Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), kind, mainData);

    assertEquals(mainName, data.getName());
    assertFalse(mainData.isReleased);

    byte[] buffer = new byte[3];
    int read = data.getStream().read(buffer);
    assertEquals(buffer.length, read);
    assertArrayEquals(MAIN_DATA, buffer);

    data.release();
    assertTrue(mainData.isReleased);
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  void testCombinedData(ProfilingSnapshot.Kind kind) throws IOException {
    String mainName = "main";
    String auxName = "auxiliary";
    TestRecordingData mainData =
        new TestRecordingData(
            mainName, Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), kind, MAIN_DATA);
    TestRecordingData auxiliaryData =
        new TestRecordingData(
            auxName,
            Instant.now(),
            Instant.now().plus(5, ChronoUnit.MINUTES),
            kind,
            AUXILIARY_DATA);

    AuxiliaryRecordingData data =
        new AuxiliaryRecordingData(
            Instant.now(),
            Instant.now().plus(5, ChronoUnit.MINUTES),
            kind,
            mainData,
            auxiliaryData);

    assertEquals(mainName, data.getName());
    assertFalse(mainData.isReleased);
    assertFalse(auxiliaryData.isReleased);

    byte[] expected = new byte[6];
    System.arraycopy(MAIN_DATA, 0, expected, 0, 3);
    System.arraycopy(AUXILIARY_DATA, 0, expected, 3, 3);
    byte[] buffer = new byte[6];
    InputStream dataStream = data.getStream();
    int pos = 0;
    int read = 0;
    while ((read = dataStream.read(buffer, pos, buffer.length - pos)) > 0) {
      pos += read;
    }
    assertEquals(buffer.length, pos);
    assertArrayEquals(expected, buffer);

    data.release();
    assertTrue(mainData.isReleased);
    assertTrue(auxiliaryData.isReleased);
  }
}
