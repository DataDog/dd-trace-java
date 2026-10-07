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

import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_MODE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_MODE_DEFAULT;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import datadog.communication.otlp.OtlpCanonicalResourceAttributes;
import datadog.communication.otlp.OtlpPayload;
import datadog.communication.otlp.OtlpResponse;
import datadog.communication.otlp.OtlpSender;
import datadog.trace.api.Config;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OtlpProfileUploader implements RecordingDataListener {

  private static final Logger log = LoggerFactory.getLogger(OtlpProfileUploader.class);
  private static final int TERMINATION_TIMEOUT_SEC = 5;
  // cap on the raw JFR recording embedded as original_payload in FULL mode — beyond this the
  // converted profile is sent without the payload instead of materializing an unbounded blob
  private static final long MAX_ORIGINAL_PAYLOAD_BYTES = 64L * 1024 * 1024;

  // canonical resource attribute keys emitted from fixed configuration; a configured profiling
  // or global tag with one of these (lower-cased) keys is dropped so it cannot shadow the
  // canonical value — mirrors the tracer's OTLP traces export filtering
  private static final Set<String> CANONICAL_RESOURCE_KEYS =
      new HashSet<>(
          java.util.Arrays.asList(
              "service",
              "env",
              "version",
              "service.name",
              "deployment.environment.name",
              "service.version",
              "host.name",
              "telemetry.sdk.name",
              "telemetry.sdk.version",
              "telemetry.sdk.language"));

  private final OtlpSender sender;
  private final ExecutorService executor;
  private final boolean enabled;
  private final int terminationTimeout;
  private final ProfilingConfig.OtlpMode mode;
  private final Map<String, String> resourceAttributes;
  // null unless mode is LIGHT; its reusable buffer is guarded by exportLock
  private final LightweightOtlpEncoder lightweightEncoder;
  // serializes conversion and send: at most one recording is converted and held in memory at a
  // time, and the LIGHT encoder buffer is not overwritten while a send is still reading it
  private final ReentrantLock exportLock = new ReentrantLock();

  public OtlpProfileUploader(final Config config, final ConfigProvider configProvider) {
    this(config, configProvider, TERMINATION_TIMEOUT_SEC);
  }

  OtlpProfileUploader(
      final Config config, final ConfigProvider configProvider, int terminationTimeout) {
    boolean enabledConfig =
        configProvider.getBoolean(PROFILING_OTLP_ENABLED, PROFILING_OTLP_ENABLED_DEFAULT);
    this.terminationTimeout = terminationTimeout;
    // the sender owns OkHttp clients and connection pools — only build it when enabled.
    // A bad optional-feature configuration must not abort profiling: degrade to disabled
    // and keep the classic JFR upload.
    OtlpSender createdSender = null;
    if (enabledConfig) {
      try {
        createdSender = createSender(config);
      } catch (Exception e) {
        log.error("Failed to create OTLP profiles sender; OTLP profile export disabled", e);
      }
    }
    this.enabled = enabledConfig && createdSender != null;
    this.sender = createdSender;
    if (enabled) {
      log.warn(
          SEND_TELEMETRY,
          "OTLP profiles export is enabled. The OTLP profiles protocol is at Development maturity"
              + " (profiles specification: Alpha) and may change incompatibly; do not use it in"
              + " production.");
    }
    this.mode =
        configProvider.getEnum(
            PROFILING_OTLP_MODE, ProfilingConfig.OtlpMode.class, PROFILING_OTLP_MODE_DEFAULT);
    this.resourceAttributes = buildResourceAttributes(config);
    this.lightweightEncoder =
        mode == ProfilingConfig.OtlpMode.LIGHT
            ? new LightweightOtlpEncoder(resourceAttributes)
            : null;
    // a single worker with no queue: a recording that arrives while the previous export is still
    // running is dropped instead of being converted and buffered alongside it
    this.executor =
        new ThreadPoolExecutor(
            0,
            1,
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

  private static OtlpSender createSender(Config config) {
    OtlpSender created = OtlpProfilesSenderFactory.create(config);
    if (created == null) {
      throw new IllegalStateException(
          "Unsupported OTLP profiles protocol: " + config.getOtlpProfilesProtocol());
    }
    return created;
  }

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
    upload(type, data, handleSynchronously, null);
  }

  public void upload(RecordingType type, RecordingData data, boolean sync, Runnable onCompletion) {
    if (!enabled) {
      data.release();
      if (onCompletion != null) {
        onCompletion.run();
      }
      return;
    }
    if (sync) {
      // the sync path runs on shutdown/snapshot; bound the wait behind an in-flight async export
      // (which may be retrying) instead of blocking for its full duration
      boolean locked = false;
      try {
        locked = exportLock.tryLock(terminationTimeout, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (!locked) {
        skipExport(data, onCompletion);
        return;
      }
      try {
        // reentrant: export acquires exportLock again
        export(data, onCompletion);
      } finally {
        exportLock.unlock();
      }
      return;
    }
    try {
      executor.execute(() -> export(data, onCompletion));
    } catch (RejectedExecutionException e) {
      skipExport(data, onCompletion);
    }
  }

  private static void skipExport(RecordingData data, Runnable onCompletion) {
    log.warn("OTLP profile export skipped: the previous export is still in progress");
    // the export never started, but the attempt happened — count it so failures cannot exceed
    // attempts under backpressure
    OtlpTelemetry.getInstance().onProfilesExportAttempt();
    OtlpTelemetry.getInstance().onProfilesExportComplete(false);
    data.release();
    if (onCompletion != null) {
      onCompletion.run();
    }
  }

  private void export(RecordingData data, Runnable onCompletion) {
    exportLock.lock();
    try {
      try {
        OtlpPayload payload;
        long conversionNanos;
        try {
          long conversionStartNanos = System.nanoTime();
          payload = convertToOtlp(data);
          conversionNanos = System.nanoTime() - conversionStartNanos;
        } finally {
          // the payload is fully materialized in memory, so the recording is no longer needed
          data.release();
        }
        // LIGHT mode does not convert the recording (it exports the raw JFR); recording its
        // encoding time would skew the conversion timing telemetry
        if (mode != ProfilingConfig.OtlpMode.LIGHT) {
          OtlpTelemetry.getInstance().onProfilesConversion(conversionNanos);
        }
        if (payload == null) {
          // skipped by the LIGHT-mode size cap — nothing to export
          return;
        }
        log.debug(
            "JFR to OTLP conversion took {} ms (mode={}, bytes={})",
            TimeUnit.NANOSECONDS.toMillis(conversionNanos),
            mode,
            payload.getContentLength());
        send(payload);
      } catch (Exception | LinkageError e) {
        // not rethrown so that the classic JFR upload continues independently;
        // LinkageError covers JVMs where the jafar parser classes cannot link
        log.error("Failed to upload OTLP profile", e);
      } finally {
        if (onCompletion != null) {
          onCompletion.run();
        }
      }
    } finally {
      exportLock.unlock();
    }
  }

  private void send(OtlpPayload payload) {
    try {
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
    }
  }

  // canonical attributes are shared with the tracer's OTLP traces export
  private static Map<String, String> buildResourceAttributes(Config config) {
    Map<String, String> attributes = new LinkedHashMap<>();
    OtlpCanonicalResourceAttributes.visit(config, attributes::put);

    // merge the user-configured tags (getMergedProfilingTags: global + profiling + runtime +
    // host tags, the same set the classic uploader sends); keys already emitted as canonical
    // attributes above are skipped
    config
        .getMergedProfilingTags()
        .forEach(
            (key, value) -> {
              if (value == null
                  || value.isEmpty()
                  || attributes.containsKey(key)
                  || CANONICAL_RESOURCE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                return;
              }
              attributes.put(key, value);
            });
    return Collections.unmodifiableMap(attributes);
  }

  private OtlpPayload convertToOtlp(RecordingData data) throws IOException {
    if (mode == ProfilingConfig.OtlpMode.LIGHT) {
      return convertLightweight(data);
    }

    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    boolean includePayload = mode == ProfilingConfig.OtlpMode.FULL;
    converter.setResourceAttributes(resourceAttributes);

    Path jfrFile = data.getPath();
    if (jfrFile != null) {
      applyPayloadCap(converter, jfrFile, includePayload);
      converter.addFile(jfrFile, data.getStart(), data.getEnd());
      return new OtlpPayload(
          ByteBuffer.wrap(converter.convert(JfrToOtlpConverter.Kind.PROTO)),
          OtlpPayload.PROTOBUF_CONTENT_TYPE);
    }

    Path tempDir = TempLocationManager.getInstance().getTempDir();
    Path temp = Files.createTempFile(tempDir, "dd-otlp-", ".jfr");
    // the copy, the conversion, and the temp-file cleanup must share one try/finally —
    // a failure while streaming would leak the temp file with the full JFR recording
    try {
      try (InputStream stream = data.getStream()) {
        Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      applyPayloadCap(converter, temp, includePayload);
      converter.addFile(temp, data.getStart(), data.getEnd());
      return new OtlpPayload(
          ByteBuffer.wrap(converter.convert(JfrToOtlpConverter.Kind.PROTO)),
          OtlpPayload.PROTOBUF_CONTENT_TYPE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  // the converted profile plus the embedded raw recording is buffered as one heap byte[];
  // cap the payload so an oversized recording is exported converted-only
  private static void applyPayloadCap(JfrToOtlpConverter converter, Path jfrFile, boolean wanted) {
    if (!wanted) {
      return;
    }
    try {
      long size = Files.size(jfrFile);
      if (size > MAX_ORIGINAL_PAYLOAD_BYTES) {
        log.warn(
            "JFR recording of {} bytes exceeds the original_payload cap of {} bytes; "
                + "exporting the converted profile without the original payload",
            size,
            MAX_ORIGINAL_PAYLOAD_BYTES);
        return;
      }
    } catch (IOException e) {
      log.warn("Could not stat JFR recording; exporting without the original payload", e);
      return;
    }
    converter.setIncludeOriginalPayload(true);
  }

  private OtlpPayload convertLightweight(RecordingData data) throws IOException {
    Path jfrFile = data.getPath();
    if (jfrFile != null) {
      return encodeLightweight(jfrFile, data.getStart(), data.getEnd());
    }

    // Fallback: save stream to temp file, then encode; copy, encode, and cleanup share one
    // try/finally so a failed copy cannot leak the temp file.
    Path tempDir = TempLocationManager.getInstance().getTempDir();
    Path temp = Files.createTempFile(tempDir, "dd-otlp-", ".jfr");
    try {
      try (InputStream stream = data.getStream()) {
        Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      return encodeLightweight(temp, data.getStart(), data.getEnd());
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  // LIGHT mode exports the raw recording as the profile content, so the recording size bounds
  // the payload — recordings above the cap are skipped instead of buffering an unbounded blob
  private OtlpPayload encodeLightweight(Path jfrFile, Instant start, Instant end)
      throws IOException {
    long jfrSize = Files.size(jfrFile);
    if (jfrSize > MAX_ORIGINAL_PAYLOAD_BYTES) {
      log.warn(
          "JFR recording of {} bytes exceeds the {} byte LIGHT-mode export cap; "
              + "skipping OTLP profile export",
          jfrSize,
          MAX_ORIGINAL_PAYLOAD_BYTES);
      return null;
    }
    ByteBuffer encoded = lightweightEncoder.encode(jfrFile, start, end);
    return new OtlpPayload(encoded, OtlpPayload.PROTOBUF_CONTENT_TYPE);
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
