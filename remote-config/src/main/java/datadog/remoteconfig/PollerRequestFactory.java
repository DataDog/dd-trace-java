package datadog.remoteconfig;

import com.squareup.moshi.Moshi;
import datadog.remoteconfig.tuf.RemoteConfigRequest;
import datadog.remoteconfig.tuf.RemoteConfigRequest.CachedTargetFile;
import datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory for creating OkHttp requests */
public class PollerRequestFactory {
  private static final String HEADER_DD_API_KEY = "DD-API-KEY";
  private static final String HEADER_CONTAINER_ID = "Datadog-Container-ID";

  private static final Logger log = LoggerFactory.getLogger(PollerRequestFactory.class);

  private final String clientId = UUID.randomUUID().toString();
  private final String runtimeId;
  private final String serviceName;
  private final String apiKey;
  private final String env;
  private final String ddVersion;
  private final String hostName;
  private final String tracerVersion;
  private final String containerId;
  final HttpUrl url;
  private final Moshi moshi;

  public PollerRequestFactory(
      Config config, String tracerVersion, String containerId, String url, Moshi moshi) {
    this.runtimeId = getRuntimeId(config);
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.apiKey = config.getApiKey();
    this.env = config.getEnv();
    this.ddVersion = config.getVersion();
    this.hostName = config.getHostName();
    this.tracerVersion = tracerVersion;
    this.containerId = containerId;
    this.url = parseUrl(url);
    this.moshi = moshi;
  }

  private static String getRuntimeId(Config config) {
    String runtimeId = config.getRuntimeId();
    if (runtimeId == null || runtimeId.length() == 0) {
      log.debug("runtimeId not configured, generating a new UUID");
      runtimeId = UUID.randomUUID().toString();
    }
    return runtimeId;
  }

  private static HttpUrl parseUrl(String url) {
    HttpUrl httpUrl = HttpUrl.parse(url);
    if (httpUrl == null) {
      throw new IllegalArgumentException("Cannot parse url");
    }
    return httpUrl;
  }

  public Request newConfigurationRequest(
      Collection<String> productNames,
      ClientState clientState,
      Collection<CachedTargetFile> cachedTargetFiles) {
    Request.Builder requestBuilder = new Request.Builder().url(this.url).get();
    MediaType applicationJson = MediaType.parse("application/json");
    RequestBody requestBody =
        RequestBody.create(
            applicationJson,
            buildRemoteConfigRequestJson(productNames, clientState, cachedTargetFiles));
    requestBuilder.post(requestBody);
    if (this.apiKey != null) {
      requestBuilder.addHeader(HEADER_DD_API_KEY, this.apiKey);
    }
    if (containerId != null && !containerId.isEmpty()) {
      requestBuilder.addHeader(HEADER_CONTAINER_ID, containerId);
    }
    return requestBuilder.build();
  }

  private String buildRemoteConfigRequestJson(
      Collection<String> productNames,
      ClientState clientState,
      Collection<CachedTargetFile> cachedTargetFiles) {
    RemoteConfigRequest rcRequest =
        RemoteConfigRequest.newRequest(
            this.clientId,
            this.runtimeId,
            this.tracerVersion,
            productNames,
            this.serviceName,
            this.env,
            this.ddVersion,
            buildRequestTags(),
            clientState,
            cachedTargetFiles);

    return moshi.adapter(RemoteConfigRequest.class).toJson(rcRequest);
  }

  private List<String> buildRequestTags() {
    List<String> tags =
        Config.get().getGlobalTags().entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .collect(Collectors.toList());
    tags.addAll(
        Arrays.asList(
            "env:" + this.env,
            "version:" + this.ddVersion,
            "tracer_version:" + this.tracerVersion,
            "host_name:" + this.hostName));

    return tags;
  }
}
