package com.datadog.crashtracking;

import static datadog.common.socket.SocketUtils.discoverApmSocket;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_HOST;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PASSWORD;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_PORT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_PROXY_USERNAME;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT;

import com.squareup.moshi.JsonWriter;
import datadog.common.container.ContainerInfo;
import datadog.common.process.PidHelper;
import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.common.version.VersionInfo;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentProxySelector;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Crash Reporter implementation */
public class CrashUploader {

  private static final Logger log = LoggerFactory.getLogger(CrashUploader.class);

  private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
  private static final MediaType APPLICATION_OCTET_STREAM =
      MediaType.parse("application/octet-stream");

  // V2.4 format
  static final String V4_CRASHUPLOAD_TAGS_PARAM = "tags_crashtracking";
  static final String V4_VERSION = "4";
  static final String V4_FAMILY = "java";

  static final String V4_EVENT_NAME = "event";
  static final String V4_ATTACHMENT_NAME = "main";

  // Header names and values
  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";
  static final String JAVA_LANG = "java";
  static final String DATADOG_META_LANG = "Datadog-Meta-Lang";
  static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  static final String JAVA_CRASHTRACKING_LIBRARY = "dd-trace-java";
  static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";

  private final OkHttpClient client;
  private final boolean agentless;
  private final String apiKey;
  private final String url;
  private final String containerId;
  private final String tags;

  public CrashUploader() {
    this(Config.get(), ConfigProvider.getInstance(), ContainerInfo.get().getContainerId());
  }

  CrashUploader(
      final Config config, final ConfigProvider configProvider, final String containerId) {
    url = config.getFinalCrashTrackingUrl();
    apiKey = config.getApiKey();
    agentless = config.isCrashTrackingAgentless();
    this.containerId = containerId;

    final Map<String, String> tagsMap = new HashMap<>(config.getMergedCrashTrackingTags());
    tagsMap.put(VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION);
    // PID can be null if we cannot find it out from the system
    if (PidHelper.PID != null) {
      tagsMap.put(PidHelper.PID_TAG, PidHelper.PID.toString());
    }
    // Comma separated tags string for V2.4 format
    tags = String.join(",", tagsToList(tagsMap));

    final Duration requestTimeout =
        Duration.ofSeconds(
            configProvider.getInteger(
                CRASH_TRACKING_UPLOAD_TIMEOUT, CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT));

    final OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(requestTimeout)
            .writeTimeout(requestTimeout)
            .readTimeout(requestTimeout)
            .callTimeout(requestTimeout)
            .proxySelector(AgentProxySelector.INSTANCE);

    final String apmSocketPath = discoverApmSocket(config);
    if (apmSocketPath != null) {
      clientBuilder.socketFactory(new UnixDomainSocketFactory(new File(apmSocketPath)));
    } else if (config.getAgentNamedPipe() != null) {
      clientBuilder.socketFactory(new NamedPipeSocketFactory(config.getAgentNamedPipe()));
    }

    if (url.startsWith("http://")) {
      // force clear text when using http to avoid failures for JVMs without TLS
      // see: https://github.com/DataDog/dd-trace-java/pull/1582
      clientBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }

    if (configProvider.getString(CRASH_TRACKING_PROXY_HOST) != null) {
      final Proxy proxy =
          new Proxy(
              Proxy.Type.HTTP,
              new InetSocketAddress(
                  configProvider.getString(CRASH_TRACKING_PROXY_HOST),
                  configProvider.getInteger(CRASH_TRACKING_PROXY_PORT)));
      clientBuilder.proxy(proxy);
      if (configProvider.getString(CRASH_TRACKING_PROXY_USERNAME) != null) {
        // Empty password by default
        final String password =
            configProvider.getString(CRASH_TRACKING_PROXY_PASSWORD) == null
                ? ""
                : configProvider.getString(CRASH_TRACKING_PROXY_PASSWORD);
        clientBuilder.proxyAuthenticator(
            (route, response) -> {
              final String credential =
                  Credentials.basic(
                      configProvider.getString(CRASH_TRACKING_PROXY_USERNAME), password);
              return response
                  .request()
                  .newBuilder()
                  .header("Proxy-Authorization", credential)
                  .build();
            });
      }
    }

    client = clientBuilder.build();
  }

  private List<String> tagsToList(final Map<String, String> tags) {
    return tags.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }

  private static final class FileEntry {
    private final String name;
    private final InputStream stream;

    public FileEntry(String name, InputStream stream) {
      this.name = name;
      this.stream = stream;
    }

    public String name() {
      return name;
    }

    public InputStream stream() {
      return stream;
    }
  }

  public void upload(@Nonnull String[] files) throws IOException {
    makeUploadRequest(
        Arrays.stream(files)
            .map(
                f -> {
                  try {
                    return new FileEntry(f, new FileInputStream(f));
                  } catch (FileNotFoundException | SecurityException e) {
                    log.error("Failed to open {}", f, e);
                    return null;
                  }
                })
            .filter(s -> s != null)
            .collect(Collectors.toMap(FileEntry::name, FileEntry::stream)));
  }

  private void makeUploadRequest(@Nonnull Map<String, InputStream> files) throws IOException {
    final RequestBody requestBody = makeRequestBody(files);

    final Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            .addHeader(DATADOG_META_LANG, JAVA_LANG)
            .addHeader(HEADER_DD_EVP_ORIGIN, JAVA_CRASHTRACKING_LIBRARY)
            .addHeader(HEADER_DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION)
            .post(requestBody);

    if (agentless && apiKey != null) {
      // we only add the api key header if we know we're doing agentless profiling. No point in
      // adding it to other agent-based requests since we know the datadog-agent isn't going to
      // make use of it.
      requestBuilder.addHeader(HEADER_DD_API_KEY, apiKey);
    }
    if (containerId != null) {
      requestBuilder.addHeader(HEADER_DD_CONTAINER_ID, containerId);
    }

    Request request = requestBuilder.build();
    client.newCall(request).execute();
  }

  private RequestBody makeRequestBody(@Nonnull Map<String, InputStream> files) throws IOException {
    Buffer out = new Buffer();
    try (JsonWriter writer = JsonWriter.of(out)) {
      writer.beginObject();
      writer.name("request_type").value("logs");
      writer.name("payload");
      writer.beginArray();
      for (Map.Entry<String, InputStream> file : files.entrySet()) {
        writer.beginObject();
        writer.name("message").value(readContent(file.getValue()));
        writer.name("level").value("ERROR");
        writer.endObject();
      }
      writer.endArray();
      writer.endObject();
    }

    return RequestBody.create(APPLICATION_JSON, out.readByteString());
  }

  private String readContent(InputStream file) throws IOException {
    try (InputStreamReader reader = new InputStreamReader(file, StandardCharsets.UTF_8)) {
      int read;
      char[] buffer = new char[1<<14];
      StringBuilder sb = new StringBuilder();
      while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
        sb.append(buffer, 0, read);
      }
      return sb.toString();
    }
  }
}
