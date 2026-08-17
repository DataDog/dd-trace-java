/*
 * Copyright 2025 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.uploader;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD_DEFAULT;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import datadog.communication.otlp.OtlpPayload;
import datadog.communication.otlp.OtlpResponse;
import datadog.communication.otlp.OtlpSender;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uploads profiles in OTLP format to the backend. Delegates transport to {@link OtlpSender},
 * reusing the tracer's shared OTLP export infrastructure (retry, compression, connection pool
 * management).
 */
public final class OtlpProfileUploader implements RecordingDataListener {

  private static final Logger log = LoggerFactory.getLogger(OtlpProfileUploader.class);
  private static final int TERMINATION_TIMEOUT_SEC = 5;
  private static final int MAX_RUNNING_REQUESTS = 10;

  private final OtlpSender sender;
  private final ExecutorService executor;
  private final int terminationTimeout;
  private final boolean includeOriginalPayload;

  public OtlpProfileUploader(final Config config, final ConfigProvider configProvider) {
    this(config, configProvider, TERMINATION_TIMEOUT_SEC);
  }

  OtlpProfileUploader(
      final Config config, final ConfigProvider configProvider, int terminationTimeout) {
    this.terminationTimeout = terminationTimeout;
    this.sender = OtlpProfilesSenderFactory.create(config);
    this.includeOriginalPayload =
        configProvider.getBoolean(
            PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD,
            PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD_DEFAULT);
    this.executor =
        new ThreadPoolExecutor(
            0,
            MAX_RUNNING_REQUESTS,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(AgentThreadFactory.AgentThread.PROFILER_HTTP_DISPATCHER),
            new ThreadPoolExecutor.AbortPolicy());
    log.debug(
        "OTLP profile uploader initialized: endpoint={}, protocol={}",
        config.getOtlpProfilesEndpoint(),
        config.getOtlpProfilesProtocol());
  }

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
    upload(type, data, handleSynchronously, null);
  }

  /**
   * Upload profile data in OTLP format.
   *
   * @param type Recording type
   * @param data Recording data to upload
   * @param sync Whether to upload synchronously
   * @param onCompletion Optional callback on completion
   */
  public void upload(RecordingType type, RecordingData data, boolean sync, Runnable onCompletion) {
    try {
      byte[] otlpBytes = convertToOtlp(data);
      OtlpPayload payload =
          new OtlpPayload(ByteBuffer.wrap(otlpBytes), OtlpPayload.PROTOBUF_CONTENT_TYPE);

      if (sync) {
        sendAndRelease(payload, data, onCompletion);
      } else {
        try {
          executor.execute(() -> sendAndRelease(payload, data, onCompletion));
        } catch (RejectedExecutionException e) {
          log.warn("OTLP profile upload rejected: too many concurrent requests");
          data.release();
          if (onCompletion != null) {
            onCompletion.run();
          }
        }
      }
    } catch (Exception e) {
      // Conversion or request creation failed. Release this uploader's reference (the caller
      // still holds the base reference and will release it via the downstream listener).
      // The exception is intentionally not rethrown so that JFR upload continues independently.
      log.error("Failed to upload OTLP profile", e);
      data.release();
      if (onCompletion != null) {
        onCompletion.run();
      }
    }
  }

  private void sendAndRelease(OtlpPayload payload, RecordingData data, Runnable onCompletion) {
    try {
      if (sender == null) {
        log.warn("OTLP profile upload skipped: no sender available for configured protocol");
        return;
      }
      OtlpTelemetry.getInstance().onProfilesExportAttempt();
      OtlpResponse response = sender.send(payload);
      OtlpTelemetry.getInstance().onProfilesExportComplete(response.success());
      if (!response.success()) {
        log.warn(
            "OTLP profile upload failed: status={}",
            response.status().isPresent() ? response.status().getAsInt() : "unknown");
      }
    } catch (Exception e) {
      log.error("OTLP profile upload failed", e);
      OtlpTelemetry.getInstance().onProfilesExportComplete(false);
    } finally {
      data.release();
      if (onCompletion != null) {
        onCompletion.run();
      }
    }
  }

  private byte[] convertToOtlp(RecordingData data) throws IOException {
    // Create a fresh converter per call — JfrToOtlpConverter is not thread-safe
    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    converter.setIncludeOriginalPayload(includeOriginalPayload);

    // Prefer file-based parsing if available (more efficient)
    Path jfrFile = data.getPath();
    if (jfrFile != null) {
      converter.addFile(jfrFile, data.getStart(), data.getEnd());
      return converter.convert(JfrToOtlpConverter.Kind.PROTO);
    }

    // Fallback: save stream to temp file in managed temp directory
    Path tempDir = TempLocationManager.getInstance().getTempDir();
    Path temp = Files.createTempFile(tempDir, "dd-otlp-", ".jfr");
    try {
      Files.copy(data.getStream(), temp);
      converter.addFile(temp, data.getStart(), data.getEnd());
      return converter.convert(JfrToOtlpConverter.Kind.PROTO);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public void shutdown() {
    log.debug("Shutting down OTLP profile uploader");
    executor.shutdown();
    try {
      if (!executor.awaitTermination(terminationTimeout, TimeUnit.SECONDS)) {
        log.warn("OTLP uploader executor did not terminate in {} seconds", terminationTimeout);
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
    if (sender != null) {
      sender.shutdown();
    }
  }
}
