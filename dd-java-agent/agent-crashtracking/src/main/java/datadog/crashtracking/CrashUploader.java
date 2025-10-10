package datadog.crashtracking;

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
import datadog.crashtracking.dto.CrashLog;
import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.PidHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
  static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  static final String JAVA_TRACING_LIBRARY = "dd-trace-java";
  static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";
  static final String HEADER_DD_TELEMETRY_API_VERSION = "DD-Telemetry-API-Version";
  static final String TELEMETRY_API_VERSION = "v2";
  static final String HEADER_DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  static final String TELEMETRY_REQUEST_TYPE = "logs";

  private static final MediaType APPLICATION_JSON =
      MediaType.get("application/json; charset=utf-8");
  private static final MediaType APPLICATION_OCTET_STREAM =
      MediaType.parse("application/octet-stream");

  private final Config config;
  private final ConfigManager.StoredConfig storedConfig;

  private final OkHttpClient telemetryClient;
  private final HttpUrl telemetryUrl;
  private final boolean agentless;
  private final String tags;

  public CrashUploader(@Nonnull final ConfigManager.StoredConfig storedConfig) {
    this(Config.get(), storedConfig);
  }

  CrashUploader(
      @NonNull final Config config, @Nonnull final ConfigManager.StoredConfig storedConfig) {
    this.config = config;
    this.storedConfig = storedConfig;

    telemetryUrl = HttpUrl.get(config.getFinalCrashTrackingTelemetryUrl());
    agentless = config.isCrashTrackingAgentless();

    final StringBuilder tagsBuilder =
        new StringBuilder(storedConfig.tags != null ? storedConfig.tags : "");
    if (!tagsBuilder.toString().isEmpty()) {
      tagsBuilder.append(",");
    }
    tagsBuilder.append(VersionInfo.LIBRARY_VERSION_TAG).append('=').append(VersionInfo.VERSION);
    // PID can be empty if we cannot find it out from the system
    if (!PidHelper.getPid().isEmpty()) {
      tagsBuilder.append(",").append(DDTags.PID_TAG).append('=').append(PidHelper.getPid());
    }
    tags = tagsBuilder.toString();

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

  public void upload(@Nonnull List<Path> files) throws IOException {
    for (Path file : files) {
      uploadToLogs(file);
      uploadToTelemetry(file);
    }
  }

  @SuppressForbidden
  boolean uploadToLogs(@Nonnull Path file) {
    try {
      uploadToLogs(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), System.out);
    } catch (IOException e) {
      log.error("Failed to upload crash file: {}", file, e);
      return false;
    }
    return true;
  }

  void uploadToLogs(@Nonnull String message, @Nonnull PrintStream out) throws IOException {
    // print on the output, and the application/container/host log will pick it up
    try (Buffer buf = new Buffer()) {
      try (JsonWriter writer = JsonWriter.of(buf)) {
        writer.beginObject();
        writer.name("ddsource").value("crashtracker");
        writer.name("ddtags").value(tags);
        writer.name("hostname").value(config.getHostName());
        writer.name("service").value(storedConfig.service);
        writer.name("version").value(storedConfig.version);
        writer.name("env").value(storedConfig.env);
        writer.name("message").value(message);
        writer.name("level").value("ERROR");
        writer.name("error");
        writer.beginObject();
        writer.name("kind").value(extractErrorKind(message));
        writer.name("message").value(extractErrorMessage(message));
        writer.name("stack").value(extractErrorStackTrace(message, false));
        writer.endObject();
        writer.endObject();
      }

      out.println(buf.readByteString().utf8());
    }
  }

  // @VisibleForTesting
  @SuppressForbidden
  static String extractErrorKind(String fileContent) {
    Matcher matcher = ERROR_MESSAGE_PATTERN.matcher(fileContent);
    if (!matcher.find()) {
      System.err.println("No match found for error.kind");
      return null;
    }

    if (matcher.group().startsWith("# There is insufficient memory")) {
      return "OutOfMemory";
    }
    return "NativeCrash";
  }

  private static final Pattern ERROR_MESSAGE_PATTERN =
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
    Matcher matcher = ERROR_MESSAGE_PATTERN.matcher(fileContent);
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
        .map(s -> s.replaceFirst("^#\\s*", "").trim())
        .collect(Collectors.joining("\n"))
        .trim();
  }

  private String extractErrorStackTrace(String fileContent, boolean redact) {
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
        if (!redact || next.contains("libjvm.so") || next.contains("libjavaProfiler")) {
          stacktrace.append(next);
        } else {
          stacktrace.append(next.charAt(0)).append("  [redacted frame]");
        }
        stacktrace.append('\n');
      } else {
        foundStart = next.startsWith("Native frames:");
      }
    }
    return "";
  }

  private String extractErrorStackTrace(String fileContent) {
    return extractErrorStackTrace(fileContent, true);
  }

  boolean uploadToTelemetry(@Nonnull Path file) {
    try {
      String content = new String(Files.readAllBytes(file), Charset.defaultCharset());
      handleCall(makeTelemetryRequest(content));
    } catch (IOException e) {
      log.error("Failed to upload crash file: {}", file, e);
      return false;
    }
    return true;
  }

  private Call makeTelemetryRequest(@Nonnull String content) throws IOException {
    final RequestBody requestBody = makeTelemetryRequestBody(content);

    final Map<String, String> headers = new HashMap<>();
    // Set chunked transfer
    MediaType contentType = requestBody.contentType();
    if (contentType != null) {
      headers.put("Content-Type", contentType.toString());
    }
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

  private RequestBody makeTelemetryRequestBody(@Nonnull String content) throws IOException {
    CrashLog crashLog = CrashLogParser.fromHotspotCrashLog(content);
    if (crashLog == null) {
      throw new IOException("Failed to parse crash log");
    }
    try (Buffer buf = new Buffer()) {
      try (JsonWriter writer = JsonWriter.of(buf)) {
        writer.beginObject();
        writer.name("api_version").value(TELEMETRY_API_VERSION);
        writer.name("request_type").value("logs");
        writer.name("runtime_id").value(storedConfig.runtimeId);
        writer.name("tracer_time").value(Instant.now().getEpochSecond());
        writer.name("seq_id").value(1);
        writer.name("debug").value(true);
        writer.name("origin").value("crashtracker");
        writer.name("payload");
        writer.beginArray();
        writer.beginObject();
        writer.name("message").value(crashLog.toJson());
        writer.name("level").value("ERROR");
        writer.name("tags").value("severity:crash");
        writer.name("is_sensitive").value(true);
        writer.name("is_crash").value(true);
        writer.endObject();
        writer.endArray();
        writer.name("application");
        writer.beginObject();
        writer.name("env").value(storedConfig.env);
        writer.name("language_name").value("jvm");
        writer
            .name("language_version")
            .value(SystemProperties.getOrDefault("java.version", "unknown"));
        writer.name("service_name").value(storedConfig.service);
        writer.name("service_version").value(storedConfig.version);
        writer.name("tracer_version").value(VersionInfo.VERSION);
        if (storedConfig.processTags != null) {
          writer.name("process_tags").value(storedConfig.processTags);
        }
        writer.endObject();
        writer.name("host");
        writer.beginObject();
        if (ContainerInfo.get().getContainerId() != null) {
          writer.name("container_id").value(ContainerInfo.get().getContainerId());
        }
        writer.name("hostname").value(config.getHostName());
        writer.name("env").value(storedConfig.env);
        writer.endObject();
        writer.endObject();
      }

      return RequestBody.create(APPLICATION_JSON, buf.readByteString());
    }
  }

  private void handleCall(final Call call) {
    try (Response response = call.execute()) {
      handleSuccess(call, response);
    } catch (IOException e) {
      handleFailure(e);
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

  private void handleFailure(final IOException exception) {
    log.error("Failed to upload crash files, got exception", exception);
  }
}
