package com.datadog.crashreporting;

import static datadog.trace.api.config.CrashReportingConfig.CRASH_REPORTING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.CrashReportingConfig.CRASH_REPORTING_UPLOAD_TIMEOUT_DEFAULT;
import static datadog.trace.api.config.CrashReportingConfig.CRASH_REPORTING_PROXY_HOST;
import static datadog.trace.api.config.CrashReportingConfig.CRASH_REPORTING_PROXY_PORT;
import static datadog.trace.api.config.CrashReportingConfig.CRASH_REPORTING_PROXY_USERNAME;
import static datadog.trace.api.config.CrashReportingConfig.CRASH_REPORTING_PROXY_PASSWORD;
import static datadog.common.socket.SocketUtils.discoverApmSocket;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import datadog.common.process.PidHelper;
import datadog.common.version.VersionInfo;
import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.common.container.ContainerInfo;
import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.relocate.api.IOLogger;
import datadog.trace.util.AgentProxySelector;
import datadog.trace.util.AgentThreadFactory;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Crash Reporter implementation */
public class CrashReporter {

  private static final Logger log = LoggerFactory.getLogger(CrashReporter.class);

  // V2.4 format
  static final String V4_CRASHREPORT_TAGS_PARAM = "tags_crashreporter";
  static final String V4_VERSION = "4";
  static final String V4_FAMILY = "java";

  static final String V4_EVENT_NAME = "event";
  static final String V4_EVENT_FILENAME = V4_EVENT_NAME + ".json";
  static final String V4_ATTACHMENT_NAME = "main";
  static final String V4_ATTACHMENT_FILENAME = V4_ATTACHMENT_NAME + ".jfr";

  // Header names and values
  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";
  static final String JAVA_LANG = "java";
  static final String DATADOG_META_LANG = "Datadog-Meta-Lang";
  static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  static final String JAVA_CRASHREPORTING_LIBRARY = "dd-trace-java";
  static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";

  private final OkHttpClient client;
  private final boolean agentless;
  private final String apiKey;
  private final String url;
  private final String containerId;

  /**
   * Main entry point into crash reporter. This gets invoked through -XX:OnError="java
   * com.datadog.crashreporting.agent.CrashReporter ..."
   */
  public static void main(String[] args) throws IOException {
    final Config config = Config.get();
    final ConfigProvider configProvider = ConfigProvider.getInstance();

    new CrashReporter(config, configProvider).upload(args);
  }

  public CrashReporter(final Config config, final ConfigProvider configProvider) {
    this(
        config,
        configProvider, 
        ContainerInfo.get().getContainerId());
  }

  CrashReporter(
      final Config config,
      final ConfigProvider configProvider,
      final String containerId) {
    url = config.getFinalCrashReportingUrl();
    apiKey = config.getApiKey();
    agentless = config.isCrashReportingAgentless();
    this.containerId = containerId;

    final Map<String, String> tagsMap = new HashMap<>(config.getMergedCrashReportingTags());
    tagsMap.put(VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION);
    // PID can be null if we cannot find it out from the system
    if (PidHelper.PID != null) {
      tagsMap.put(PidHelper.PID_TAG, PidHelper.PID.toString());
    }
    // Comma separated tags string for V2.4 format
    String tags = String.join(",", tagsToList(tagsMap));

    final Duration requestTimeout = Duration.ofSeconds(configProvider.getInt(CRASH_REPORTING_UPLOAD_TIMEOUT, CRASH_REPORTING_UPLOAD_TIMEOUT_DEFAULT));

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

    if (configProvider.getString(CRASH_REPORTING_PROXY_HOST) != null) {
      final Proxy proxy =
          new Proxy(
              Proxy.Type.HTTP,
              new InetSocketAddress(
                  configProvider.getString(CRASH_REPORTING_PROXY_HOST), configProvider.getInt(CRASH_REPORTING_PROXY_PORT)));
      clientBuilder.proxy(proxy);
      if (configProvider.getString(CRASH_REPORTING_PROXY_USERNAME) != null) {
        // Empty password by default
        final String password =
            configProvider.getString(CRASH_REPORTING_PROXY_PASSWORD) == null ? "" : configProvider.getString(CRASH_REPORTING_PROXY_PASSWORD);
        clientBuilder.proxyAuthenticator(
            (route, response) -> {
              final String credential =
                  Credentials.basic(configProvider.getString(CRASH_REPORTING_PROXY_USERNAME), password);
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

  private static final class FileStreamEntry {
    private final String file;
    private final InputStream stream;
    public FileStreamEntry(String file, InputStream stream) {
      this.file = file;
      this.stream = stream;
    }
    public String file() { return file; }
    public InputStream stream() { return stream; }
  }

  void upload(String[] files) {
    makeUploadRequest(
        Arrays.stream(files)
            .map(f -> {
              try {
                return new FileStreamEntry(f, new FileInputStream(f));
              } catch (FileNotFoundException | SecurityException e) {
                log.error("Failed to open {}", f, e);
                return null;
              }
            })
            .filter(s -> s != null)
            .collect(Collectors.toMap(FileStreamEntry::file, FileStreamEntry::stream)));
  }

  private void makeUploadRequest(Map<String, InputStream> files) {
    final RequestBody requestBody = makeRequestBody(files);

    final Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            // Set chunked transfer
            .addHeader("Transfer-Encoding", "chunked")
            // Note: this header is used to disable tracing of profiling requests
            .addHeader(DATADOG_META_LANG, JAVA_LANG)
            .addHeader(HEADER_DD_EVP_ORIGIN, JAVA_CRASHREPORTING_LIBRARY)
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

    client
      .newCall(requestBuilder.build())
      .execute();
  }

  // private byte[] createEvent(@Nonnull final String file) {
  //   final StringBuilder os = new StringBuilder();
  //   os.append("{");
  //   os.append("\"attachments\":[\"" + file + "\"],");
  //   os.append("\"" + V4_CRASHREPORT_TAGS_PARAM + "\":\"" + tags + "\",");
  //   os.append("\"family\":\"" + V4_FAMILY + "\",");
  //   os.append("\"version\":\"" + V4_VERSION + "\"");
  //   os.append("}");
  //   return os.toString().getBytes();
  // }

  private RequestBody makeRequestBody(Map<String, InputStream> files) {
    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder().setType(MultipartBody.FORM);
    
    // final byte[] event = createEvent(data);
    // final RequestBody eventBody = RequestBody.create(APPLICATION_JSON, event);
    // bodyBuilder.addPart(EVENT_HEADER, eventBody);
    // bodyBuilder.addPart(V4_DATA_HEADERS, body);
    return bodyBuilder.build();
  }
}
