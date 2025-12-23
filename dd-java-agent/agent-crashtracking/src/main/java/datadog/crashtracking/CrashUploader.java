package datadog.crashtracking;

import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_HOST;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PASSWORD;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PORT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_USERNAME;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AgentThread.CRASHTRACKING_HTTP_DISPATCHER;
import static datadog.trace.util.TraceUtils.normalizeServiceName;
import static datadog.trace.util.TraceUtils.normalizeTagValue;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.squareup.moshi.JsonWriter;
import datadog.common.container.ContainerInfo;
import datadog.common.version.VersionInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.dto.ErrorData;
import datadog.crashtracking.dto.OSInfo;
import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.PidHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
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
  static final String HEADER_DD_EVP_SUBDOMAIN = "X-Datadog-EVP-Subdomain";
  static final String ERROR_TRACKING_INTAKE = "error-tracking-intake";
  static final String HEADER_DD_TELEMETRY_API_VERSION = "DD-Telemetry-API-Version";
  static final String TELEMETRY_API_VERSION = "v2";
  static final String HEADER_DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  static final String TELEMETRY_REQUEST_TYPE = "logs";

  private static final MediaType APPLICATION_JSON =
      MediaType.get("application/json; charset=utf-8");

  private static final class CallResult implements Callback {
    private final String kind; // for logging

    private CallResult(String kind) {
      this.kind = kind;
    }

    @Override
    public void onFailure(Call call, IOException e) {
      log.error("Failed to upload {} to {}, got exception", kind, call.request().url(), e);
    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {
      if (response.isSuccessful()) {
        log.info(
            "Successfully uploaded the {} to {}, code = {} \"{}\"",
            kind,
            call.request().url(),
            response.code(),
            response.message());
      } else {
        log.error(
            "Failed to upload {} to {}, code = {} \"{}\", body = \"{}\"",
            kind,
            call.request().url(),
            response.code(),
            response.message(),
            response.body() != null ? response.body().string().trim() : "<null>");
      }
    }
  }

  private final Config config;
  private final ConfigManager.StoredConfig storedConfig;

  private final HttpUrl telemetryUrl;
  private final HttpUrl errorTrackingUrl;
  private final OkHttpClient uploadClient;
  private final Dispatcher dispatcher;
  private final ExecutorService executor;
  private final boolean agentless;
  private final String tags;
  private final long timeout;

  public CrashUploader(@Nonnull final ConfigManager.StoredConfig storedConfig) {
    this(Config.get(), storedConfig);
  }

  CrashUploader(
      @NonNull final Config config, @Nonnull final ConfigManager.StoredConfig storedConfig) {
    this.config = config;
    this.storedConfig = storedConfig;
    this.telemetryUrl = HttpUrl.get(config.getFinalCrashTrackingTelemetryUrl());
    this.errorTrackingUrl = HttpUrl.get(config.getFinalCrashTrackingErrorTrackingUrl());
    this.agentless = config.isCrashTrackingAgentless();
    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    this.executor =
        new ThreadPoolExecutor(
            0,
            4,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(CRASHTRACKING_HTTP_DISPATCHER));
    this.dispatcher = new Dispatcher(executor);

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

    this.timeout =
        SECONDS.toMillis(
            configProvider.getInteger(
                CRASH_TRACKING_UPLOAD_TIMEOUT, CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT));

    uploadClient =
        OkHttpUtils.buildHttpClient(
            config,
            dispatcher, /* dispatcher */
            telemetryUrl, // will be overridden in each request
            true, /* retryOnConnectionFailure */
            4, /* maxRunningRequests */ // not having one request blocking the others
            configProvider.getString(CRASH_TRACKING_PROXY_HOST),
            configProvider.getInteger(CRASH_TRACKING_PROXY_PORT),
            configProvider.getString(CRASH_TRACKING_PROXY_USERNAME),
            configProvider.getString(CRASH_TRACKING_PROXY_PASSWORD),
            timeout);
  }

  public void notifyCrashStarted(String error) {
    sendPingToTelemetry(error);
    if (storedConfig.sendToErrorTracking) {
      sendPingToErrorTracking(error);
    }
  }

  // @VisibleForTesting
  void sendPingToTelemetry(String error) {
    // send a ping message to the telemetry to notify that the crash report started
    try (Buffer buf = new Buffer();
        JsonWriter writer = JsonWriter.of(buf)) {
      writer.beginObject();
      writer.name("crash_uuid").value(storedConfig.reportUUID);
      writer.name("kind").value("Crash ping");
      writer.name("current_schema_version").value("1.0");
      writer
          .name("message")
          .value(
              "Crashtracker crash ping: " + (error != null ? error : "crash processing started"));
      writer.endObject();
      handleCall(makeTelemetryRequest(makeTelemetryRequestBody(buf.readUtf8(), true)), "ping");
    } catch (Throwable t) {
      log.error("Failed to prepare the telemetry crash ping payload", t);
    }
  }

  // @VisibleForTesting
  void sendPingToErrorTracking(String error) {
    try {
      final CrashLog ping =
          new CrashLog(
              storedConfig.reportUUID,
              false,
              ZonedDateTime.now().format(ISO_OFFSET_DATE_TIME),
              new ErrorData(
                  null,
                  "Crashtracker crash ping: "
                      + (error != null ? error : "crash processing started"),
                  null),
              null,
              OSInfo.current(),
              null,
              null,
              "1.0");
      handleCall(makeErrorTrackingRequest(makeErrorTrackingRequestBody(ping, true)), "ping");
    } catch (Throwable t) {
      log.error("Failed to prepare the error tracking crash ping payload", t);
    }
  }

  @SuppressForbidden
  public void upload(@Nonnull Path file) {
    String fileContent;
    try {
      fileContent = new String(Files.readAllBytes(file), Charset.defaultCharset());
    } catch (Throwable t) {
      log.error("Failed to collect information about the crash", t);
      return; // cannot proceed further
    }

    try {
      uploadToLogs(fileContent, System.out);
    } catch (Throwable t) {
      log.error("Unable to print the error crash as a log message", t);
    }
    try {
      remoteUpload(fileContent, true, storedConfig.sendToErrorTracking);
    } finally {
      uploadClient.dispatcher().cancelAll();
    }
  }

  // @VisibleForTesting
  void remoteUpload(
      @Nonnull String fileContent, boolean sendToTelemetry, boolean sendToErrorTracking) {
    final String uuid = storedConfig.reportUUID;
    try {
      CrashLog crashLog = CrashLogParser.fromHotspotCrashLog(uuid, fileContent);
      if (sendToTelemetry) {
        uploadToTelemetry(crashLog);
      }
      if (sendToErrorTracking) {
        uploadToErrorTracking(crashLog);
      }
    } catch (Throwable t) {
      log.error("Error while before sending remotely the crash report with uuid {}", uuid, t);
    }
    int remaining;
    long deadline = MILLISECONDS.toNanos(timeout) + System.nanoTime();
    // container that crashed
    while ((remaining = dispatcher.queuedCallsCount() + dispatcher.runningCallsCount()) > 0
        && deadline > System.nanoTime()) {
      try {
        Thread.sleep(100); // good enough for this purpose even if we overflow
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (remaining > 0) {
      dispatcher.cancelAll();
      uploadClient.connectionPool().evictAll();
      log.error(
          SEND_TELEMETRY,
          "Failed to fully send the crash report with UUID {}. Still {} calls remaining",
          uuid,
          remaining);
    }
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
      log.error("No match found for error.kind");
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
      log.error("No match found for error.message");
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

  void uploadToTelemetry(@Nonnull CrashLog crashLog) {
    try {
      handleCall(makeTelemetryRequest(makeTelemetryRequestBody(crashLog.toJson(), false)), "crash");
    } catch (Throwable t) {
      log.error("Failed to make a telemetry request", t);
    }
  }

  private Call makeTelemetryRequest(@Nonnull RequestBody requestBody) throws IOException {
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

    return uploadClient.newCall(
        OkHttpUtils.prepareRequest(telemetryUrl, headers, config, agentless)
            .post(requestBody)
            .build());
  }

  private RequestBody makeTelemetryRequestBody(@Nonnull String payload, boolean isPing)
      throws IOException {

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
        writer.name("message").value(payload);
        if (isPing) {
          writer.name("level").value("DEBUG");
          writer.name("is_sensitive").value(false);
          writer.name("tags").value(tagsForPing(storedConfig.reportUUID));
        } else {
          writer.name("level").value("ERROR");
          writer.name("tags").value("severity:crash");
          writer.name("is_sensitive").value(true);
          writer.name("is_crash").value(true);
        }
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

  void uploadToErrorTracking(@Nonnull CrashLog crashLog) {
    try {
      handleCall(makeErrorTrackingRequest(makeErrorTrackingRequestBody(crashLog, false)), "crash");
    } catch (Throwable t) {
      log.error("Failed to make a error tracking request", t);
    }
  }

  private Call makeErrorTrackingRequest(@Nonnull RequestBody requestBody) throws IOException {
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
    if (!agentless) {
      headers.put(HEADER_DD_EVP_SUBDOMAIN, ERROR_TRACKING_INTAKE);
    }

    return uploadClient.newCall(
        OkHttpUtils.prepareRequest(errorTrackingUrl, headers, config, agentless)
            .post(requestBody)
            .build());
  }

  private RequestBody makeErrorTrackingRequestBody(@Nonnull CrashLog payload, boolean isPing)
      throws IOException {
    try (Buffer buf = new Buffer()) {
      try (JsonWriter writer = JsonWriter.of(buf)) {
        writer.beginObject();
        writer.name("timestamp").value(payload.timestamp);
        writer.name("ddsource").value("crashtracker");
        // tags
        writer.name("ddtags").value(tagsForErrorTracking(payload.uuid, isPing, payload.incomplete));
        // error payload
        if (payload.error != null) {
          writer.name("error");
          writer.beginObject();
          if (!isPing) {
            writer.name("is_crash").value(true);
          }
          writer.name("type").value(payload.error.kind);
          writer.name("message").value(payload.error.message);
          writer.name("source_type").value("crashtracking");
          if (payload.error.stack != null) {
            writer.name("stack");
            // flat write an already serialized json object
            payload.error.stack.writeAsJson(writer);
          }
          writer.endObject();
        }
        // signal info
        if (payload.sigInfo != null) {
          writer.name("sig_info");
          writer.beginObject();
          if (payload.sigInfo.address != null) {
            writer.name("si_addr").value(payload.sigInfo.address);
          }
          if (payload.sigInfo.name != null) {
            writer.name("si_signo_human_readable").value(payload.sigInfo.name);
            writer.name("si_signo").value(payload.sigInfo.number);
          }
          writer.endObject();
        }

        // os info
        if (payload.osInfo != null) {
          writer.name("os_info");
          writer.beginObject();
          writer.name("architecture").value(payload.osInfo.architecture);
          writer.name("bitness").value(payload.osInfo.bitness);
          writer.name("os_type").value(payload.osInfo.osType);
          writer
              .name("version")
              .value(
                  SystemProperties.get(
                      "os.version")); // this has been restructured under OsInfo so taking raw here
          writer.endObject();
        }
        writer.endObject();
      }
      return RequestBody.create(APPLICATION_JSON, buf.readByteString());
    }
  }

  private String tagsForErrorTracking(String uuid, boolean isPing, boolean incomplete) {
    final StringBuilder tags = new StringBuilder();
    if (storedConfig.tags != null) {
      // normally it does not happen
      tags.append(storedConfig.tags);
    } else {
      tags.append("service:")
          .append(normalizeServiceName(storedConfig.service)); // ensure the service name is there
    }
    if (isPing) {
      tags.append(",").append("is_crash_ping:true");
    } else {
      tags.append(",").append("is_crash:true");
      if (incomplete) {
        tags.append(",").append("incomplete:true");
      }
    }
    tags.append(",").append("data_schema_version:1.0");
    tags.append(",").append("language_name:jvm");
    tags.append(",")
        .append("language_version:")
        .append(normalizeTagValue(SystemProperties.getOrDefault("java.version", "unknown")));
    tags.append(",").append("tracer_version:").append(normalizeTagValue(VersionInfo.VERSION));
    tags.append(",").append("uuid:").append(uuid);
    return (tags.toString());
  }

  private String tagsForPing(String uuid) {
    final StringBuilder tags = new StringBuilder("is_crash_ping:true");
    tags.append(",").append("language_name:jvm");
    tags.append(",").append("service:").append(normalizeServiceName(storedConfig.service));
    tags.append(",")
        .append("language_version:")
        .append(normalizeTagValue(SystemProperties.getOrDefault("java.version", "unknown")));
    tags.append(",").append("tracer_version:").append(normalizeTagValue(VersionInfo.VERSION));
    tags.append(",").append("uuid:").append(uuid);
    return (tags.toString());
  }

  private void handleCall(final Call call, String kind) {
    call.enqueue(new CallResult(kind));
  }
}
