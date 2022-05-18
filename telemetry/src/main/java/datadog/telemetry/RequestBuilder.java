package datadog.telemetry;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.common.container.ContainerInfo;
import datadog.telemetry.api.ApiVersion;
import datadog.telemetry.api.Application;
import datadog.telemetry.api.Host;
import datadog.telemetry.api.Payload;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.api.Telemetry;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestBuilder {

  private static final String API_ENDPOINT = "/telemetry/proxy/api/v2/apmtelemetry";
  private static final Pattern OS_RELEASE_PATTERN =
      Pattern.compile("(?<name>[A-Z]+)=\"(?<value>[^\"]+)\"");
  private static final Path OS_RELEASE_PATH = Paths.get("/etc/os-release");

  private static final ApiVersion API_VERSION = ApiVersion.V1;
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private static final Logger log = LoggerFactory.getLogger(RequestBuilder.class);

  private static final JsonAdapter<Telemetry> JSON_ADAPTER =
      new Moshi.Builder()
          .add(new PolymorphicAdapterFactory(Payload.class))
          .build()
          .adapter(Telemetry.class);
  private static final String AGENT_VERSION;
  private static final AtomicLong SEQ_ID = new AtomicLong();

  private final HttpUrl httpUrl;
  private final Application application;
  private final Host host;
  private final String runtimeId;

  static {
    String tracerVersion = "0.0.0";
    try {
      tracerVersion = getAgentVersion();
    } catch (Throwable t) {
      log.error("Unable to get tracer version ", t);
    }

    AGENT_VERSION = tracerVersion;
  }

  public RequestBuilder(HttpUrl httpUrl) {
    this.httpUrl = httpUrl.newBuilder().addPathSegments(API_ENDPOINT).build();

    Config config = Config.get();

    this.runtimeId = config.getRuntimeId();
    this.application =
        new Application()
            .env(config.getEnv())
            .serviceName(config.getServiceName())
            .serviceVersion(config.getVersion())
            .tracerVersion(AGENT_VERSION)
            .languageName("jvm")
            .languageVersion(Platform.getLangVersion())
            .runtimeName(Platform.getRuntimeVendor())
            .runtimeVersion(Platform.getRuntimeVersion())
            .runtimePatches(Platform.getRuntimePatches());

    ContainerInfo containerInfo = ContainerInfo.get();
    this.host =
        new Host()
            .hostname(HostnameGuessing.getHostname())
            .os(System.getProperty("os.name"))
            .osVersion(getOsVersion())
            .kernelName(Uname.UTS_NAME.sysname())
            .kernelRelease(Uname.UTS_NAME.release())
            .kernelVersion(Uname.UTS_NAME.version())
            .containerId(containerInfo.getContainerId());
  }

  private String getOsVersion() {
    if (Files.isRegularFile(OS_RELEASE_PATH)) {
      String name = null;
      String version = null;
      try {
        List<String> lines = Files.readAllLines(OS_RELEASE_PATH, StandardCharsets.ISO_8859_1);
        for (String l : lines) {
          Matcher matcher = OS_RELEASE_PATTERN.matcher(l);
          if (!matcher.matches()) {
            continue;
          }
          String nameGroup = matcher.group("name");
          if ("NAME".equals(nameGroup)) {
            name = matcher.group("value");
          } else if ("VERSION".equals(nameGroup)) {
            version = matcher.group("value");
          }
        }
      } catch (IOException e) {
      }
      if (name != null && version != null) {
        return name + " " + version;
      }
    }
    return System.getProperty("os.version");
  }

  public Request build(RequestType requestType) {
    return build(requestType, null);
  }

  public Request build(RequestType requestType, Payload payload) {
    Telemetry telemetry =
        new Telemetry()
            .apiVersion(API_VERSION)
            .requestType(requestType)
            .tracerTime(System.currentTimeMillis() / 1000L)
            .runtimeId(runtimeId)
            .seqId(SEQ_ID.incrementAndGet())
            .application(application)
            .host(host)
            .payload(payload);

    String json = JSON_ADAPTER.toJson(telemetry);
    RequestBody body = RequestBody.create(JSON, json);

    return new Request.Builder()
        .url(httpUrl)
        .addHeader("Content-Type", JSON.toString())
        .addHeader("DD-Telemetry-API-Version", API_VERSION.toString())
        .addHeader("DD-Telemetry-Request-Type", requestType.toString())
        .post(body)
        .build();
  }

  private static String getAgentVersion() throws IOException {
    final StringBuilder sb = new StringBuilder(32);
    ClassLoader cl = ClassLoader.getSystemClassLoader();
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                cl.getResourceAsStream("dd-java-agent.version"), StandardCharsets.ISO_8859_1))) {
      for (int c = reader.read(); c != -1; c = reader.read()) {
        sb.append((char) c);
      }
    }

    return sb.toString().trim();
  }
}

class HostnameGuessing {
  private static final Path PROC_HOSTNAME =
      FileSystems.getDefault().getPath("/proc/sys/kernel/hostname");
  private static final Path ETC_HOSTNAME = FileSystems.getDefault().getPath("/etc/hostname");
  private static final List<Path> HOSTNAME_FILES = Arrays.asList(PROC_HOSTNAME, ETC_HOSTNAME);

  private static final Logger log = LoggerFactory.getLogger(RequestBuilder.class);

  public static String getHostname() {
    String hostname = Uname.UTS_NAME.nodename();

    if (hostname == null) {
      for (Path file : HOSTNAME_FILES) {
        hostname = tryReadFile(file);
        if (null != hostname) {
          break;
        }
      }
    }

    if (hostname == null) {
      try {
        hostname = getHostNameFromLocalHost();
      } catch (UnknownHostException e) {
        // purposefully left empty
      }
    }

    if (hostname != null) {
      hostname = hostname.trim();
    } else {
      log.warn("Could not determine hostname");
      hostname = "";
    }

    return hostname;
  }

  private static String getHostNameFromLocalHost() throws UnknownHostException {
    return InetAddress.getLocalHost().getHostName();
  }

  private static String tryReadFile(Path file) {
    String content = null;
    if (Files.isRegularFile(file)) {
      try {
        byte[] bytes = Files.readAllBytes(file);
        content = new String(bytes, StandardCharsets.ISO_8859_1);
      } catch (IOException e) {
        log.debug("Could not read {}", file, e);
      }
    }
    return content;
  }
}
