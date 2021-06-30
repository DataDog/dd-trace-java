package com.datadog.profiling.auxiliary;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuxiliaryRecordingDataTest {
  private static final byte[] MAIN_DATA = new byte[] {1, 2, 3};
  private static final byte[] AUXILIARY_DATA = new byte[] {4, 5, 6};

  private static class TestRecordingData extends RecordingData {
    private final String name;
    private final byte[] testData;
    volatile boolean isReleased = false;

    public TestRecordingData(String name, Instant start, Instant end, byte[] testData) {
      super(start, end);
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

  @Test
  void testNullMaindata() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuxiliaryRecordingData(Instant.now(), Instant.now(), null));
  }

  @Test
  void testMainDataThrowing() throws IOException {
    RecordingData mainData = Mockito.mock(RecordingData.class);
    RecordingData auxiliaryData = Mockito.mock(RecordingData.class);

    byte[] auxDataRaw = new byte[10];
    Mockito.when(mainData.getStream()).thenThrow(new IOException());
    Mockito.when(auxiliaryData.getStream())
        .thenReturn(new RecordingInputStream(new ByteArrayInputStream(auxDataRaw)));

    AuxiliaryRecordingData combined =
        new AuxiliaryRecordingData(Instant.now(), Instant.now(), mainData, auxiliaryData);

    assertThrows(IOException.class, () -> readFromStream(combined.getStream()));
  }

  @Test
  void testAuxiliaryDataThrowing() throws IOException {
    RecordingData mainData = Mockito.mock(RecordingData.class);
    RecordingData auxiliaryData = Mockito.mock(RecordingData.class);

    byte[] mainDataRaw = new byte[10];
    Mockito.when(mainData.getStream())
        .thenReturn(new RecordingInputStream(new ByteArrayInputStream(mainDataRaw)));
    Mockito.when(auxiliaryData.getStream()).thenThrow(new IOException());

    AuxiliaryRecordingData combined =
        new AuxiliaryRecordingData(Instant.now(), Instant.now(), mainData, auxiliaryData);

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

  @Test
  void testMainDataOnly() throws IOException {
    String mainName = "main";
    TestRecordingData mainData =
        new TestRecordingData(
            mainName, Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), MAIN_DATA);
    AuxiliaryRecordingData data =
        new AuxiliaryRecordingData(
            Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), mainData);

    assertEquals(mainName, data.getName());
    assertFalse(mainData.isReleased);

    byte[] buffer = new byte[3];
    int read = data.getStream().read(buffer);
    assertEquals(buffer.length, read);
    assertArrayEquals(MAIN_DATA, buffer);

    data.release();
    assertTrue(mainData.isReleased);
  }

  @Test
  void testCombinedData() throws IOException {
    String mainName = "main";
    String auxName = "auxiliary";
    TestRecordingData mainData =
        new TestRecordingData(
            mainName, Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), MAIN_DATA);
    TestRecordingData auxiliaryData =
        new TestRecordingData(
            auxName, Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), AUXILIARY_DATA);

    AuxiliaryRecordingData data =
        new AuxiliaryRecordingData(
            Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES), mainData, auxiliaryData);

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
