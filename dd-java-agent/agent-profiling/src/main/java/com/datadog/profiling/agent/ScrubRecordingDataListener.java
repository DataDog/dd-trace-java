package com.datadog.profiling.agent;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.scrubber.JfrScrubber;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link RecordingDataListener} decorator that scrubs sensitive fields from JFR recording data
 * before delegating to the next listener. When the recording data is already file-backed (eg.
 * ddprof), the existing file is used directly as scrub input to avoid stream materialization.
 */
final class ScrubRecordingDataListener implements RecordingDataListener {
  private static final Logger log = LoggerFactory.getLogger(ScrubRecordingDataListener.class);

  private final RecordingDataListener delegate;
  private final JfrScrubber scrubber;
  private final Path tempDir;
  private final boolean failOpen;

  ScrubRecordingDataListener(
      RecordingDataListener delegate, JfrScrubber scrubber, Path tempDir, boolean failOpen) {
    this.delegate = delegate;
    this.scrubber = scrubber;
    this.tempDir = tempDir;
    this.failOpen = failOpen;
  }

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
    Path tempInput = null;
    Path tempOutput = null;
    try {
      // Use the existing file path when available (eg. ddprof), otherwise materialize the stream
      Path inputPath = data.getPath();

      if (inputPath == null) {
        tempInput = Files.createTempFile(tempDir, "dd-scrub-in-", ".jfr");
        Files.copy(data.getStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);
        inputPath = tempInput;
      }

      tempOutput = Files.createTempFile(tempDir, "dd-scrub-out-", ".jfr");
      scrubber.scrubFile(inputPath, tempOutput);

      if (tempInput != null) {
        Files.deleteIfExists(tempInput);
        tempInput = null;
      }

      ScrubbedRecordingData scrubbed = new ScrubbedRecordingData(data, tempOutput);
      tempOutput = null; // ownership transferred to ScrubbedRecordingData
      data.release();
      delegate.onNewData(type, scrubbed, handleSynchronously);
    } catch (Exception e) {
      cleanupQuietly(tempInput);
      cleanupQuietly(tempOutput);
      if (failOpen) {
        log.warn(SEND_TELEMETRY, "JFR scrubbing failed, uploading unscrubbed data", e);
        delegate.onNewData(type, data, handleSynchronously);
      } else {
        log.error(SEND_TELEMETRY, "JFR scrubbing failed, skipping upload", e);
        data.release();
      }
    }
  }

  private static void cleanupQuietly(Path path) {
    if (path != null) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  /** File-backed {@link RecordingData} wrapping a scrubbed output file. */
  static final class ScrubbedRecordingData extends RecordingData {
    private final String name;
    private final Path scrubbedFile;

    ScrubbedRecordingData(RecordingData original, Path scrubbedFile) {
      super(original.getStart(), original.getEnd(), original.getKind());
      this.name = original.getName();
      this.scrubbedFile = scrubbedFile;
    }

    @Nonnull
    @Override
    public RecordingInputStream getStream() throws IOException {
      return new RecordingInputStream(Files.newInputStream(scrubbedFile));
    }

    @Override
    public void release() {
      try {
        Files.deleteIfExists(scrubbedFile);
      } catch (IOException e) {
        log.debug("Failed to clean up scrubbed recording file: {}", scrubbedFile, e);
      }
    }

    @Nonnull
    @Override
    public String getName() {
      return name;
    }

    @Override
    public Path getPath() {
      return scrubbedFile;
    }
  }
}
