package com.datadog.profiling.agent;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.scrubber.DefaultScrubDefinition;
import com.datadog.profiling.scrubber.JfrScrubber;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
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
  private static final Path SCRUB_SUBDIR = Paths.get("scrub");

  private final RecordingDataListener delegate;
  private final JfrScrubber scrubber;
  private final boolean failOpen;
  private final Path tempDirOverride;

  /**
   * Wraps {@code delegate} with a scrubbing listener. Invoked via reflection from ProfilingAgent.
   */
  static RecordingDataListener wrap(
      RecordingDataListener delegate, List<String> excludeEventTypes, boolean failOpen) {
    return new ScrubRecordingDataListener(
        delegate, DefaultScrubDefinition.create(excludeEventTypes), failOpen);
  }

  ScrubRecordingDataListener(
      RecordingDataListener delegate, JfrScrubber scrubber, boolean failOpen) {
    this(delegate, scrubber, failOpen, null);
  }

  // visible for testing
  ScrubRecordingDataListener(
      RecordingDataListener delegate, JfrScrubber scrubber, boolean failOpen, Path tempDir) {
    this.delegate = delegate;
    this.scrubber = scrubber;
    this.failOpen = failOpen;
    this.tempDirOverride = tempDir;
  }

  private Path getTempDir() {
    if (tempDirOverride != null) {
      return tempDirOverride;
    }
    return TempLocationManager.getInstance().getTempDir(SCRUB_SUBDIR);
  }

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
    Path tempInput = null;
    Path tempOutput = null;
    try {
      Path tempDir = getTempDir();
      // Use the existing file path when available (eg. ddprof), otherwise materialize the stream
      Path inputPath = data.getPath();

      if (inputPath == null) {
        tempInput = Files.createTempFile(tempDir, "dd-scrub-in-", ".jfr");
        try (RecordingInputStream in = data.getStream()) {
          Files.copy(in, tempInput, StandardCopyOption.REPLACE_EXISTING);
        }
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
      // Release the original recording eagerly — the scrubbed copy is now the source of truth.
      // The delegate will call release() on ScrubbedRecordingData to clean up the output file.
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
