package datadog.remote_config;

import com.squareup.moshi.Moshi;
import datadog.remote_config.tuf.RemoteConfigRequest;
import datadog.remote_config.tuf.RemoteConfigRequest.CachedTargetFile;
import datadog.remote_config.tuf.RemoteConfigRequest.ClientInfo.ClientState;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.util.Collection;
import java.util.UUID;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory for creating OkHttp requests */
public class PollerRequestFactory {
  static final String HEADER_DD_API_KEY = "DD-API-KEY";

  private static final Logger log = LoggerFactory.getLogger(PollerRequestFactory.class);

  private final String clientId = UUID.randomUUID().toString();
  private final String runtimeId;
  private final String serviceName;
  private final String apiKey;
  private final String env;
  private final String ddVersion;
  private final String tracerVersion;
  final HttpUrl url;
  private final Moshi moshi;

  public PollerRequestFactory(Config config, String tracerVersion, String url, Moshi moshi) {
    this.runtimeId = getRuntimeId(config);
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.apiKey = config.getApiKey();
    this.env = config.getEnv();
    this.ddVersion = config.getVersion();
    this.tracerVersion = tracerVersion;
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
            clientState,
            cachedTargetFiles);

    return moshi.adapter(RemoteConfigRequest.class).toJson(rcRequest);
  }
}
