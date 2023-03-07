package com.datadog.crashtracking;

import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_HOST;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PASSWORD;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PORT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_USERNAME;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT;

import com.squareup.moshi.JsonWriter;
import datadog.common.container.ContainerInfo;
import datadog.common.version.VersionInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.PidHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public final class CrashUploader {

  private static final Logger log = LoggerFactory.getLogger(CrashUploader.class);

  // Header names and values
  static final String JAVA_LANG = "java";
  static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  static final String JAVA_TRACING_LIBRARY = "dd-trace-java";
  static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";
  static final String HEADER_DD_TELEMETRY_API_VERSION = "DD-Telemetry-API-Version";
  static final String TELEMETRY_API_VERSION = "v1";
  static final String HEADER_DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  static final String TELEMETRY_REQUEST_TYPE = "logs";

  private static final MediaType APPLICATION_JSON =
      MediaType.get("application/json; charset=utf-8");
  private static final MediaType APPLICATION_OCTET_STREAM =
      MediaType.parse("application/octet-stream");

  private final Config config;

  private final OkHttpClient telemetryClient;
  private final HttpUrl telemetryUrl;
  private final boolean agentless;
  private final String tags;

  public CrashUploader() {
    this(Config.get());
  }

  CrashUploader(final Config config) {
    this.config = config;

    telemetryUrl = HttpUrl.get(config.getFinalCrashTrackingTelemetryUrl());
    agentless = config.isCrashTrackingAgentless();

    final Map<String, String> tagsMap = new HashMap<>(config.getMergedCrashTrackingTags());
    tagsMap.put(VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION);
    // PID can be empty if we cannot find it out from the system
    if (!PidHelper.getPid().isEmpty()) {
      tagsMap.put(DDTags.PID_TAG, PidHelper.getPid());
    }
    // Comma separated tags string for V2.4 format
    tags = tagsToString(tagsMap);

    ConfigProvider configProvider = config.configProvider();

    telemetryClient =
        OkHttpUtils.buildHttpClient(
            config,
            null, /* dispatcher */
            telemetryUrl,
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
    List<String> filesContent = new ArrayList<>(files.size());
    for (InputStream file : files) {
      filesContent.add(readContent(file));
    }
    uploadToLogs(filesContent);
    uploadToTelemetry(filesContent);
  }

  void uploadToLogs(@Nonnull List<String> filesContent) throws IOException {
    uploadToLogs(filesContent, System.out);
  }

  void uploadToLogs(@Nonnull List<String> filesContent, @Nonnull PrintStream out)
      throws IOException {
    // print on the output, and the application/container/host log will pick it up
    for (String message : filesContent) {
      try (Buffer buf = new Buffer()) {
        try (JsonWriter writer = JsonWriter.of(buf)) {
          writer.beginObject();
          writer.name("ddsource").value("crashtracker");
          writer.name("ddtags").value(tags);
          writer.name("hostname").value(config.getHostName());
          writer.name("service").value(config.getServiceName());
          writer.name("message").value(message);
          writer.name("level").value("ERROR");
          writer.name("error");
          writer.beginObject();
          writer.name("kind").value(extractErrorKind(message));
          writer.name("message").value(extractErrorMessage(message));
          writer.name("stack").value(extractErrorStackTrace(message));
          writer.endObject();
          writer.endObject();
        }

        out.println(buf.readByteString().utf8());
      }
    }
  }

  private static final Pattern errorKindPattern =
      Pattern.compile(
          String.join(
              "",
              "^",
              "(",
              "# A fatal error has been detected by the Java Runtime Environment:",
              "|",
              "# There is insufficient memory for the Java Runtime Environment to continue\\.",
              ")",
              "$"),
          Pattern.DOTALL);

  // @VisibleForTesting
  static String extractErrorKind(String fileContent) {
    Matcher matcher = errorMessagePattern.matcher(fileContent);
    if (!matcher.find()) {
      System.err.println("No match found for error.kind");
      return null;
    }

    if (matcher.group().startsWith("# There is insufficient memory")) {
      return "OutOfMemory";
    }
    return "NativeCrash";
  }

  private static final Pattern errorMessagePattern =
      Pattern.compile(
          String.join(
              "",
              "^",
              "(",
              "# A fatal error has been detected by the Java Runtime Environment:",
              "|",
              "# There is insufficient memory for the Java Runtime Environment to continue\\.",
              ")",
              "\\n",
              "(",
              ".*, pid=-?\\d+, tid=-?\\d+",
              ")",
              "$"),
          Pattern.DOTALL | Pattern.MULTILINE);

  // @VisibleForTesting
  @SuppressForbidden
  static String extractErrorMessage(String fileContent) {
    Matcher matcher = errorMessagePattern.matcher(fileContent);
    if (!matcher.find()) {
      System.err.println("No match found for error.message");
      return null;
    }
    return Arrays.stream(matcher.group().split(System.lineSeparator()))
        .filter(
            s ->
                !s.equals("# A fatal error has been detected by the Java Runtime Environment:")
                    && !s.equals(
                        "# There is insufficient memory for the Java Runtime Environment to continue."))
        .map(s -> s.replaceFirst("^#\\s*", ""))
        .map(s -> s.trim())
        .collect(Collectors.joining("\n"))
        .trim();
  }

  private static final Pattern ERROR_STACK_TRACE_PATTERN =
      Pattern.compile(
          "(Native frames: \\(J=compiled Java code, j=interpreted, Vv=VM code, C=native code\\)\n)(.+)(\n\\s*$)",
          Pattern.DOTALL | Pattern.MULTILINE);

  private String extractErrorStackTrace(String fileContent) {
    Scanner scanner = new Scanner(fileContent);
    StringBuilder stacktrace = new StringBuilder();
    boolean foundStart = false;
    while (scanner.hasNext()) {
      String next = scanner.nextLine();
      if (foundStart && next.isEmpty()) {
        if (stacktrace.length() > 0) {
          // remove trailing newline
          stacktrace.setLength(stacktrace.length() - 1);
        }
        return stacktrace.toString();
      }
      if (foundStart) {
        stacktrace.append(next).append('\n');
      } else {
        foundStart = next.startsWith("Native frames:");
      }
    }
    return "";
  }

  void uploadToTelemetry(@Nonnull List<String> filesContent) throws IOException {
    handleCall(makeTelemetryRequest(filesContent));
  }

  private Call makeTelemetryRequest(@Nonnull List<String> filesContent) throws IOException {
    final RequestBody requestBody = makeTelemetryRequestBody(filesContent);

    final Map<String, String> headers = new HashMap<>();
    // Set chunked transfer
    headers.put("Content-Type", requestBody.contentType().toString());
    headers.put("Content-Length", Long.toString(requestBody.contentLength()));
    headers.put("Transfer-Encoding", "chunked");
    headers.put(HEADER_DD_EVP_ORIGIN, JAVA_TRACING_LIBRARY);
    headers.put(HEADER_DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION);
    headers.put(HEADER_DD_TELEMETRY_API_VERSION, TELEMETRY_API_VERSION);
    headers.put(HEADER_DD_TELEMETRY_REQUEST_TYPE, TELEMETRY_REQUEST_TYPE);

    return telemetryClient.newCall(
        OkHttpUtils.prepareRequest(telemetryUrl, headers, config, agentless)
            .post(requestBody)
            .build());
  }

  private RequestBody makeTelemetryRequestBody(@Nonnull List<String> filesContent)
      throws IOException {
    try (Buffer buf = new Buffer()) {
      try (JsonWriter writer = JsonWriter.of(buf)) {
        writer.beginObject();
        writer.name("api_version").value(TELEMETRY_API_VERSION);
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
        for (String message : filesContent) {
          writer.beginObject();
          writer.name("message").value(message);
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

      return RequestBody.create(APPLICATION_JSON, buf.readByteString());
    }
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

  private void handleCall(final Call call) {
    try (Response response = call.execute()) {
      handleSuccess(call, response);
    } catch (IOException e) {
      handleFailure(call, e);
    }
  }

  private void handleSuccess(final Call call, final Response response) throws IOException {
    if (response.isSuccessful()) {
      log.info(
          "Successfully uploaded the crash files to {}, code = {} \"{}\"",
          call.request().url(),
          response.code(),
          response.message());
    } else {
      log.error(
          "Failed to upload crash files to {}, code = {} \"{}\", body = \"{}\"",
          call.request().url(),
          response.code(),
          response.message(),
          response.body() != null ? response.body().string().trim() : "<null>");
    }
  }

  private void handleFailure(final Call call, final IOException exception) {
    log.error("Failed to upload crash files, got exception: {}", exception.getMessage(), exception);
  }
}
