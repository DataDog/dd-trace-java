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

import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_COMPRESSION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_COMPRESSION_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_URL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_URL_DEFAULT;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import datadog.common.version.VersionInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.relocate.api.IOLogger;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.TempLocationManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uploads profiles in OTLP format to the backend. */
public final class OtlpProfileUploader implements RecordingDataListener {

  private static final Logger log = LoggerFactory.getLogger(OtlpProfileUploader.class);
  private static final MediaType APPLICATION_PROTOBUF = MediaType.get("application/x-protobuf");
  private static final int TERMINATION_TIMEOUT_SEC = 5;
  private static final int MAX_RUNNING_REQUESTS = 10;

  // Header names
  private static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  private static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";
  private static final String JAVA_TRACING_LIBRARY = "dd-trace-java";

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final int terminationTimeout;
  private final JfrToOtlpConverter converter;

  // Configuration (read from ConfigProvider)
  private final boolean enabled;
  private final boolean includeOriginalPayload;
  private final String otlpUrl;
  private final boolean compressionEnabled;

  public OtlpProfileUploader(final Config config, final ConfigProvider configProvider) {
    this(config, configProvider, new IOLogger(log), TERMINATION_TIMEOUT_SEC);
  }

  /**
   * Constructor visible for testing.
   *
   * @param config Config instance (for upload timeout)
   * @param configProvider ConfigProvider for reading OTLP-specific config
   * @param ioLogger Logger for I/O operations
   * @param terminationTimeout Timeout for executor service termination
   */
  OtlpProfileUploader(
      final Config config,
      final ConfigProvider configProvider,
      final IOLogger ioLogger,
      final int terminationTimeout) {
    this.terminationTimeout = terminationTimeout;

    // Read OTLP configuration from ConfigProvider
    this.enabled =
        configProvider.getBoolean(PROFILING_OTLP_ENABLED, PROFILING_OTLP_ENABLED_DEFAULT);
    this.includeOriginalPayload =
        configProvider.getBoolean(
            PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD,
            PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD_DEFAULT);
    this.otlpUrl = configProvider.getString(PROFILING_OTLP_URL, PROFILING_OTLP_URL_DEFAULT);
    this.compressionEnabled =
        configProvider.getBoolean(
            PROFILING_OTLP_COMPRESSION_ENABLED, PROFILING_OTLP_COMPRESSION_ENABLED_DEFAULT);

    Duration uploadTimeout = Duration.ofSeconds(config.getProfilingUploadTimeout());

    // Create converter and configure it
    this.converter = new JfrToOtlpConverter();
    this.converter.setIncludeOriginalPayload(includeOriginalPayload);

    // Create OkHttp client with custom dispatcher
    this.okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            MAX_RUNNING_REQUESTS,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(AgentThreadFactory.AgentThread.PROFILER_HTTP_DISPATCHER),
            new ThreadPoolExecutor.AbortPolicy());

    final Dispatcher dispatcher = new Dispatcher(okHttpExecutorService);
    dispatcher.setMaxRequests(MAX_RUNNING_REQUESTS);
    dispatcher.setMaxRequestsPerHost(MAX_RUNNING_REQUESTS);

    // Derive OTLP endpoint URL for buildHttpClient
    HttpUrl parsedUrl;
    if (!otlpUrl.isEmpty()) {
      parsedUrl = HttpUrl.parse(otlpUrl);
    } else {
      // Derive from agent URL: http://agent:8126 → http://agent:4318/v1/profiles
      String agentUrl = config.getFinalProfilingUrl();
      HttpUrl agentParsed = HttpUrl.parse(agentUrl);
      if (agentParsed != null) {
        parsedUrl = agentParsed.newBuilder().port(4318).encodedPath("/v1/profiles").build();
      } else {
        parsedUrl = HttpUrl.parse("http://localhost:4318/v1/profiles");
      }
    }

    this.client =
        OkHttpUtils.buildHttpClient(
            config,
            dispatcher,
            parsedUrl,
            true, // agentless mode
            MAX_RUNNING_REQUESTS,
            config.getProfilingProxyHost(),
            config.getProfilingProxyPort(),
            config.getProfilingProxyUsername(),
            config.getProfilingProxyPassword(),
            uploadTimeout.toMillis());

    log.debug("OTLP profile uploader initialized: enabled={}, url={}", enabled, parsedUrl);
  }

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
    if (!enabled) {
      data.release();
      return;
    }

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
      // Convert JFR to OTLP
      byte[] otlpData = convertToOtlp(data);

      // Create HTTP request
      Request request = createOtlpRequest(otlpData);

      // Upload
      if (sync) {
        uploadSync(request, data, onCompletion);
      } else {
        uploadAsync(request, data, onCompletion);
      }
    } catch (Exception e) {
      log.error("Failed to upload OTLP profile", e);
      data.release();
      if (onCompletion != null) {
        onCompletion.run();
      }
    }
  }

  /**
   * Convert JFR recording to OTLP protobuf format.
   *
   * @param data Recording data
   * @return OTLP protobuf bytes
   * @throws IOException if conversion fails
   */
  private byte[] convertToOtlp(RecordingData data) throws IOException {
    // Reset converter for reuse
    converter.reset();

    // Prefer file-based parsing if available (more efficient)
    Path jfrFile = data.getFile();
    if (jfrFile != null) {
      converter.addFile(jfrFile, data.getStart(), data.getEnd());
    } else {
      // Fallback: save stream to temp file in managed temp directory
      Path tempDir = TempLocationManager.getInstance().getTempDir();
      Path temp = Files.createTempFile(tempDir, "dd-otlp-", ".jfr");
      try {
        Files.copy(data.getStream(), temp);
        converter.addFile(temp, data.getStart(), data.getEnd());
      } finally {
        Files.deleteIfExists(temp);
      }
    }

    // Convert to OTLP protobuf
    return converter.convert(JfrToOtlpConverter.Kind.PROTO);
  }

  /**
   * Create HTTP request for OTLP upload.
   *
   * @param otlpData OTLP protobuf bytes
   * @return OkHttp Request
   * @throws IOException if compression fails
   */
  private Request createOtlpRequest(byte[] otlpData) throws IOException {
    String url = getOtlpEndpointUrl();

    // Compress if configured
    byte[] payload = compress(otlpData);

    RequestBody body = RequestBody.create(APPLICATION_PROTOBUF, payload);

    Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/x-protobuf")
            .header(HEADER_DD_EVP_ORIGIN, JAVA_TRACING_LIBRARY)
            .header(HEADER_DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION);

    // Add compression header if enabled
    if (compressionEnabled) {
      requestBuilder.header("Content-Encoding", "gzip");
    }

    return requestBuilder.build();
  }

  /**
   * Get OTLP endpoint URL. If not configured, derives from agent URL using standard OTLP port/path.
   *
   * @return OTLP endpoint URL
   */
  private String getOtlpEndpointUrl() {
    if (!otlpUrl.isEmpty()) {
      return otlpUrl;
    }

    // Derive from agent URL: http://agent:8126 → http://agent:4318/v1/profiles
    String agentUrl = Config.get().getFinalProfilingUrl();
    HttpUrl parsed = HttpUrl.parse(agentUrl);
    if (parsed != null) {
      return parsed.newBuilder().port(4318).encodedPath("/v1/profiles").build().toString();
    }

    // Fallback
    return "http://localhost:4318/v1/profiles";
  }

  /**
   * Compress data using GZIP if compression is enabled.
   *
   * @param data Uncompressed data
   * @return Compressed data (or original if compression is disabled)
   * @throws IOException if compression fails
   */
  private byte[] compress(byte[] data) throws IOException {
    if (!compressionEnabled) {
      return data;
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
      gzipOut.write(data);
    }
    return baos.toByteArray();
  }

  /**
   * Upload synchronously.
   *
   * @param request HTTP request
   * @param data Recording data (for cleanup)
   * @param onCompletion Optional callback
   */
  private void uploadSync(Request request, RecordingData data, Runnable onCompletion) {
    try (Response response = client.newCall(request).execute()) {
      handleResponse(response, data, onCompletion);
    } catch (IOException e) {
      handleFailure(e, data, onCompletion);
    }
  }

  /**
   * Upload asynchronously.
   *
   * @param request HTTP request
   * @param data Recording data (for cleanup)
   * @param onCompletion Optional callback
   */
  private void uploadAsync(Request request, RecordingData data, Runnable onCompletion) {
    client
        .newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onResponse(Call call, Response response) {
                handleResponse(response, data, onCompletion);
              }

              @Override
              public void onFailure(Call call, IOException e) {
                handleFailure(e, data, onCompletion);
              }
            });
  }

  /**
   * Handle HTTP response.
   *
   * @param response HTTP response
   * @param data Recording data (for cleanup)
   * @param onCompletion Optional callback
   */
  private void handleResponse(Response response, RecordingData data, Runnable onCompletion) {
    try {
      if (response.isSuccessful()) {
        log.debug("OTLP profile uploaded successfully: {}", response.code());
      } else {
        log.warn("OTLP profile upload failed: {} - {}", response.code(), response.message());
      }
    } finally {
      data.release();
      response.close();
      if (onCompletion != null) {
        onCompletion.run();
      }
    }
  }

  /**
   * Handle upload failure.
   *
   * @param e Exception
   * @param data Recording data (for cleanup)
   * @param onCompletion Optional callback
   */
  private void handleFailure(IOException e, RecordingData data, Runnable onCompletion) {
    log.error("OTLP profile upload failed", e);
    data.release();
    if (onCompletion != null) {
      onCompletion.run();
    }
  }

  /** Shutdown the uploader and wait for pending uploads. */
  public void shutdown() {
    log.debug("Shutting down OTLP profile uploader");
    okHttpExecutorService.shutdown();
    try {
      if (!okHttpExecutorService.awaitTermination(terminationTimeout, TimeUnit.SECONDS)) {
        log.warn("OTLP uploader executor did not terminate in {} seconds", terminationTimeout);
        okHttpExecutorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      okHttpExecutorService.shutdownNow();
    }
  }
}
