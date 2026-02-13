package com.datadog.profiling.uploader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.profiling.RecordingInputStream;
import io.airlift.compress.zstd.ZstdInputStream;
import com.datadog.profiling.utils.zstd.ZstdOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import okio.BufferedSink;
import okio.Okio;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.stubbing.Answer;

class CompressingRequestBodyTest {
  private static byte[] recordingData;

  @BeforeAll
  static void setupAll() throws Exception {
    InputStream dataStream = testRecordingStream();
    recordingData = new byte[dataStream.available()];
    IOUtils.readFully(dataStream, recordingData);
  }

  @ParameterizedTest
  @EnumSource(CompressionType.class)
  void contentLength(CompressionType compressionType) throws Exception {
    CompressingRequestBody instance =
        new CompressingRequestBody(
            compressionType, mock(CompressingRequestBody.InputStreamSupplier.class));
    assertEquals(-1, instance.contentLength());
  }

  @ParameterizedTest
  @EnumSource(CompressionType.class)
  void contentType(CompressionType compressionType) {
    CompressingRequestBody instance =
        new CompressingRequestBody(
            compressionType, mock(CompressingRequestBody.InputStreamSupplier.class));
    assertEquals(CompressingRequestBody.OCTET_STREAM, instance.contentType());
  }

  @Test
  void writeToRetryRecoverable() throws Exception {
    int expectedRetries = 1;

    assertWriteToRecoverable(faultySupplier(expectedRetries), expectedRetries);
  }

  @Test
  void writeToRetryIrrecoverable() throws Exception {
    int expectedRetries = 1;

    assertWriteToRetryIrrecoverable(faultySupplier(expectedRetries + 1), expectedRetries);
    assertWriteToRetryIrrecoverable(
        faultyStreamSupplier(expectedRetries), 0); // faulty stream is not retried
  }

  private void assertWriteToRecoverable(
      CompressingRequestBody.InputStreamSupplier faultySupplier, int expectedRetries)
      throws Exception {
    CompressingRequestBody instance =
        new CompressingRequestBody(CompressionType.OFF, faultySupplier, r -> r <= expectedRetries);
    byte[] compressed = instanceWriteAsBytes(instance);
    assertArrayEquals(recordingData, compressed);

    verify(faultySupplier, VerificationModeFactory.times(expectedRetries + 1)).get();
    assertEquals(recordingData.length, instance.getReadBytes());
    assertEquals(recordingData.length, instance.getWrittenBytes());
  }

  private void assertWriteToRetryIrrecoverable(
      CompressingRequestBody.InputStreamSupplier faultySupplier, int expectedRetries)
      throws Exception {
    CompressingRequestBody instance =
        new CompressingRequestBody(CompressionType.OFF, faultySupplier, r -> r <= expectedRetries);
    assertThrows(IOException.class, () -> instanceWriteAsBytes(instance));

    verify(faultySupplier, VerificationModeFactory.times(expectedRetries + 1)).get();
    assertEquals(0, instance.getReadBytes());
    assertEquals(0, instance.getWrittenBytes());
  }

  private CompressingRequestBody.InputStreamSupplier faultySupplier(int failingAttempts)
      throws Exception {
    CompressingRequestBody.InputStreamSupplier supplier =
        mock(CompressingRequestBody.InputStreamSupplier.class);
    AtomicInteger invocationCounter = new AtomicInteger(failingAttempts);
    when(supplier.get())
        .then(
            (Answer<InputStream>)
                invocation -> {
                  if (invocationCounter.getAndDecrement() > 0) {
                    throw new IllegalStateException();
                  }
                  return testRecordingStream();
                });
    return supplier;
  }

  private CompressingRequestBody.InputStreamSupplier faultyStreamSupplier(int failingAttempts)
      throws Exception {
    CompressingRequestBody.InputStreamSupplier supplier =
        mock(CompressingRequestBody.InputStreamSupplier.class);
    AtomicInteger invocationCounter = new AtomicInteger(failingAttempts);

    when(supplier.get())
        .then(
            (Answer<InputStream>)
                invocation ->
                    new BufferedInputStream(testRecordingStream()) {
                      int byteCounter = 300; // read first 300 bytes without error

                      @Override
                      public synchronized int read() throws IOException {
                        if (--byteCounter <= 0 && invocationCounter.getAndDecrement() > 0) {
                          throw new IllegalStateException();
                        }
                        return super.read();
                      }

                      @Override
                      public synchronized int read(byte[] b, int off, int len) throws IOException {
                        byteCounter -= len;
                        if (byteCounter <= 0 && invocationCounter.getAndDecrement() > 0) {
                          throw new IllegalStateException();
                        }
                        return super.read(b, off, len);
                      }
                    });
    return supplier;
  }

  @ParameterizedTest
  @EnumSource(CompressionType.class)
  void writeTo(CompressionType compressionType) throws IOException {
    CompressingRequestBody instance =
        new CompressingRequestBody(
            compressionType, CompressingRequestBodyTest::testRecordingStream);

    byte[] compressed = instanceWriteAsBytes(instance);
    BufferedInputStream compressedStream =
        new BufferedInputStream(new ByteArrayInputStream(compressed));

    switch (compressionType) {
      case OFF:
        {
          assertFalse(CompressingRequestBody.isCompressed(compressedStream));
          assertArrayEquals(recordingData, compressed);
          assertEquals(recordingData.length, instance.getReadBytes());
          assertEquals(recordingData.length, instance.getWrittenBytes());
          break;
        }
      case LZ4:
        {
          assertTrue(CompressingRequestBody.isLz4(compressedStream));
          byte[] uncompressed = IOUtils.toByteArray(new LZ4FrameInputStream(compressedStream));
          assertArrayEquals(recordingData, uncompressed);
          assertEquals(recordingData.length, instance.getReadBytes());
          assertEquals(compressed.length, instance.getWrittenBytes());
          break;
        }
      case GZIP:
        {
          assertTrue(CompressingRequestBody.isGzip(compressedStream));
          byte[] uncompressed = IOUtils.toByteArray(new GZIPInputStream(compressedStream));
          assertArrayEquals(recordingData, uncompressed);
          assertEquals(recordingData.length, instance.getReadBytes());
          assertEquals(compressed.length, instance.getWrittenBytes());
          break;
        }
      case ON:
      case ZSTD:
        {
          assertTrue(CompressingRequestBody.isZstd(compressedStream));
          byte[] uncompressed = IOUtils.toByteArray(new ZstdInputStream(compressedStream));
          assertArrayEquals(recordingData, uncompressed);
          assertEquals(recordingData.length, instance.getReadBytes());
          assertEquals(compressed.length, instance.getWrittenBytes());
          break;
        }
    }
  }

  @ParameterizedTest
  @EnumSource(CompressionType.class)
  void writeToRecompression(CompressionType targetType) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    OutputStream compressedStream = null;
    for (CompressionType type : EnumSet.allOf(CompressionType.class)) {
      if (type == CompressionType.OFF) {
        continue;
      }
      switch (type) {
        case LZ4:
        case ON:
          {
            compressedStream = new LZ4FrameOutputStream(baos);
            break;
          }
        case GZIP:
          {
            compressedStream = new GZIPOutputStream(baos);
            break;
          }
        case ZSTD:
          {
            compressedStream = new ZstdOutputStream(baos);
            break;
          }
      }
      assertNotNull(compressedStream);

      IOUtils.copy(new ByteArrayInputStream(recordingData), compressedStream);
      compressedStream.close();

      byte[] compressedInput = baos.toByteArray();

      CompressingRequestBody instance =
          new CompressingRequestBody(
              targetType,
              () -> new RecordingInputStream(new ByteArrayInputStream(compressedInput)));
      byte[] compressedOutput = instanceWriteAsBytes(instance);

      assertArrayEquals(compressedInput, compressedOutput);
    }
  }

  private static byte[] instanceWriteAsBytes(CompressingRequestBody instance) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (BufferedSink sink = Okio.buffer(Okio.sink(baos))) {
      instance.writeTo(sink);
    }
    return baos.toByteArray();
  }

  private static RecordingInputStream testRecordingStream() {
    return new RecordingInputStream(
        CompressingRequestBodyTest.class.getResourceAsStream("/test-recording.jfr"));
  }
}
