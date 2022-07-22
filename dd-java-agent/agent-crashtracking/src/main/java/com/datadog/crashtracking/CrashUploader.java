package com.datadog.crashtracking;

import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_HOST;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PASSWORD;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PORT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_USERNAME;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT;

import com.squareup.moshi.JsonWriter;
import datadog.common.container.ContainerInfo;
import datadog.common.process.PidHelper;
import datadog.common.version.VersionInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Crash Reporter implementation */
public class CrashUploader {

  private static final Logger log = LoggerFactory.getLogger(CrashUploader.class);

  // Header names and values
  static final String JAVA_LANG = "java";
  static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  static final String JAVA_TRACING_LIBRARY = "dd-trace-java";
  static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";
  static final String HEADER_DD_TELEMETRY_API_VERSION = "DD-Telemetry-API-Version";
  static final String API_VERSION = "v1";
  static final String HEADER_DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  static final String REQUEST_TYPE = "logs";

  private static final MediaType APPLICATION_JSON =
      MediaType.get("application/json; charset=utf-8");
  private static final MediaType APPLICATION_OCTET_STREAM =
      MediaType.parse("application/octet-stream");

  private final Config config;
  private final ConfigProvider configProvider;

  private final OkHttpClient client;
  private final boolean agentless;
  private final HttpUrl url;
  private final String tags;

  public CrashUploader() {
    this(Config.get(), ConfigProvider.getInstance());
  }

  CrashUploader(final Config config, final ConfigProvider configProvider) {
    this.config = config;
    this.configProvider = configProvider;

    url = HttpUrl.get(config.getFinalCrashTrackingUrl());
    agentless = config.isCrashTrackingAgentless();

    final Map<String, String> tagsMap = new HashMap<>(config.getMergedCrashTrackingTags());
    tagsMap.put(VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION);
    // PID can be null if we cannot find it out from the system
    if (PidHelper.PID != null) {
      tagsMap.put(PidHelper.PID_TAG, PidHelper.PID.toString());
    }
    // Comma separated tags string for V2.4 format
    tags = tagsToString(tagsMap);

    client =
        OkHttpUtils.buildHttpClient(
            config,
            null, /* dispatcher */
            url,
            true, /* retryOnConnectionFailure */
            null, /* maxRunningRequests */
            configProvider.getString(CRASH_TRACKING_PROXY_HOST),
            configProvider.getInteger(CRASH_TRACKING_PROXY_PORT),
            configProvider.getString(CRASH_TRACKING_PROXY_USERNAME),
            configProvider.getString(CRASH_TRACKING_PROXY_PASSWORD),
            TimeUnit.SECONDS.toMillis(
                configProvider.getInteger(
                    CRASH_TRACKING_UPLOAD_TIMEOUT, CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT)));
  }

  private String tagsToString(final Map<String, String> tags) {
    return tags.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.joining(","));
  }

  public void upload(@Nonnull List<InputStream> files) throws IOException {
    Call call = makeRequest(files);
    try {
      handleSuccess(call, call.execute());
    } catch (IOException e) {
      handleFailure(call, e);
    }
  }

  private Call makeRequest(@Nonnull List<InputStream> files) throws IOException {
    final RequestBody requestBody = makeRequestBody(files);

    final Map<String, String> headers = new HashMap<>();
    // Set chunked transfer
    headers.put("Content-Type", requestBody.contentType().toString());
    headers.put("Content-Length", Long.toString(requestBody.contentLength()));
    headers.put("Transfer-Encoding", "chunked");
    headers.put(HEADER_DD_EVP_ORIGIN, JAVA_TRACING_LIBRARY);
    headers.put(HEADER_DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION);
    headers.put(HEADER_DD_TELEMETRY_API_VERSION, API_VERSION);
    headers.put(HEADER_DD_TELEMETRY_REQUEST_TYPE, REQUEST_TYPE);

    return client.newCall(
        OkHttpUtils.prepareRequest(url, headers, config, agentless).post(requestBody).build());
  }

  private RequestBody makeRequestBody(@Nonnull List<InputStream> files) throws IOException {
    Buffer out = new Buffer();
    try (JsonWriter writer = JsonWriter.of(out)) {
      writer.beginObject();

      writer.name("api_version").value(API_VERSION);
      writer.name("request_type").value("logs");
      writer
          .name("runtime_id")
          // randomly generated, https://xkcd.com/221/
          .value("5e5b1180-2a0b-41a6-bed2-bc341d19f853");
      writer.name("tracer_time").value(Instant.now().getEpochSecond());
      writer.name("seq_id").value(1);
      writer.name("debug").value(true);
      writer.name("payload");
      writer.beginArray();
      for (InputStream file : files) {
        writer.beginObject();
        writer.name("message").value(readContent(file));
        writer.name("level").value("ERROR");
        writer.endObject();
      }
      writer.endArray();
      writer.name("application");
      writer.beginObject();
      writer.name("env").value(config.getEnv());
      writer.name("language_name").value(JAVA_LANG);
      writer.name("language_version").value(System.getProperty("java.version", "unknown"));
      writer.name("service_name").value(config.getServiceName());
      writer.name("service_version").value(config.getVersion());
      writer.name("tracer_version").value(VersionInfo.VERSION);
      writer.endObject();
      writer.name("host");
      writer.beginObject();
      if (ContainerInfo.get().getContainerId() != null) {
        writer.name("container_id").value(ContainerInfo.get().getContainerId());
      }
      writer.name("hostname").value(config.getHostName());
      writer.name("env").value(config.getEnv());
      writer.endObject();
      writer.endObject();
    }

    return RequestBody.create(APPLICATION_JSON, out.readByteString());
  }

  private String readContent(InputStream file) throws IOException {
    try (InputStreamReader reader = new InputStreamReader(file, StandardCharsets.UTF_8)) {
      int read;
      char[] buffer = new char[1 << 14];
      StringBuilder sb = new StringBuilder();
      while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
        sb.append(buffer, 0, read);
      }
      return sb.toString();
    }
  }

  private void handleSuccess(final Call call, final Response response) throws IOException {
    if (response.isSuccessful()) {
      log.info(
          "Successfully uploaded the crash files, code = {} \"{}\"",
          response.code(),
          response.message());
    } else {
      log.error(
          "Failed to upload crash files, code = {} \"{}\", body = \"{}\"",
          response.code(),
          response.message(),
          response.body() != null ? response.body().string().trim() : "<null>");
    }
  }

  private void handleFailure(final Call call, final IOException exception) {
    log.error("Failed to upload crash files, got exception: {}", exception.getMessage(), exception);
  }
}
