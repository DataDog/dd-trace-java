package com.datadog.debugger.poller;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.tuf.RemoteConfigRequest;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.util.UUID;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory for creating OkHttp requests */
class PollerRequestFactory {
  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final String HEADER_DEBUGGER_TRACKING_ID = "X-Datadog-HostId";

  private static final Logger LOGGER = LoggerFactory.getLogger(PollerRequestFactory.class);

  static Request newConfigurationRequest(Config config, String url, Moshi moshi) {
    Request.Builder requestBuilder = new Request.Builder().url(parseUrl(url)).get();
    MediaType applicationJson = MediaType.parse("application/json");
    RequestBody requestBody =
        RequestBody.create(applicationJson, buildRemoteConfigJson(config, moshi));
    requestBuilder.post(requestBody);
    return requestBuilder.build();
  }

  private static String getTrackingId(Config config) {
    String runtimeId = config.getRuntimeId();
    if (runtimeId == null || runtimeId.length() == 0) {
      LOGGER.debug("runtimeId not configured, generating a new UUID");
      runtimeId = UUID.randomUUID().toString();
    }
    return runtimeId;
  }

  private static HttpUrl parseUrl(String appProbesUrl) {
    HttpUrl httpUrl = HttpUrl.parse(appProbesUrl);
    if (httpUrl == null) {
      throw new IllegalArgumentException("Cannot parse url");
    }
    return httpUrl;
  }

  private static String buildRemoteConfigJson(Config config, Moshi moshi) {
    String trackingId = getTrackingId(config);
    RemoteConfigRequest rcRequest =
        RemoteConfigRequest.newRequest(
            trackingId,
            DebuggerAgent.getAgentVersion(),
            config.getServiceName(),
            config.getEnv(),
            config.getVersion());
    return moshi.adapter(RemoteConfigRequest.class).toJson(rcRequest);
  }
}
