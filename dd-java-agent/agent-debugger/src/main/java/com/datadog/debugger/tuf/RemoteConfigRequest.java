package com.datadog.debugger.tuf;

import com.squareup.moshi.Json;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Handles requests to Remote Configuration */
public class RemoteConfigRequest {

  public static RemoteConfigRequest newRequest(
      String trackingId,
      String tracerVersion,
      String serviceName,
      String serviceEnv,
      String serviceVersion) {
    ClientInfo.TracerInfo tracerInfo =
        new RemoteConfigRequest.ClientInfo.TracerInfo(
            trackingId, tracerVersion, serviceName, serviceEnv, serviceVersion);
    ClientInfo clientInfo =
        new RemoteConfigRequest.ClientInfo(trackingId, tracerVersion, tracerInfo);
    return new RemoteConfigRequest(clientInfo);
  }

  private final ClientInfo client;

  @Json(name = "cached_target_files")
  private final List<String> cachedTargetFiles = Collections.emptyList();

  public RemoteConfigRequest(ClientInfo client) {
    this.client = client;
  }

  public ClientInfo getClient() {
    return this.client;
  }

  /** Stores client information for Remote Configuration */
  public static class ClientInfo {
    private final String id;
    private final String name = "live-debugger-agent";
    private final List<String> products = Collections.singletonList("LIVE_DEBUGGING");
    private final Map<String, String> state = Collections.emptyMap();
    private final String version;

    @Json(name = "client_tracer")
    private final TracerInfo tracerInfo;

    @Json(name = "is_tracer")
    private final Boolean isTracer = true;

    public ClientInfo(String id, String version, TracerInfo tracerInfo) {
      this.id = id;
      this.version = version;
      this.tracerInfo = tracerInfo;
    }

    public String getId() {
      return id;
    }

    public TracerInfo getTracerClient() {
      return tracerInfo;
    }

    public String getVersion() {
      return version;
    }

    public String getName() {
      return name;
    }

    public List<String> getProducts() {
      return products;
    }

    public Map<String, String> getState() {
      return state;
    }

    public static class TracerInfo {
      @Json(name = "runtime_id")
      private final String tracerId;

      @Json(name = "tracer_version")
      private final String tracerVersion;

      @Json(name = "service")
      private final String serviceName;

      @Json(name = "env")
      private final String serviceEnv;

      @Json(name = "app_version")
      private final String serviceVersion;

      private final String language = "jvm";

      public TracerInfo(
          String tracerId,
          String tracerVersion,
          String serviceName,
          String serviceEnv,
          String serviceVersion) {
        this.tracerId = tracerId;
        this.tracerVersion = tracerVersion;
        this.serviceName = serviceName;
        this.serviceEnv = serviceEnv;
        this.serviceVersion = serviceVersion;
      }

      public String getTracerId() {
        return tracerId;
      }

      public String getTracerVersion() {
        return tracerVersion;
      }

      public String getServiceName() {
        return serviceName;
      }

      public String getServiceEnv() {
        return serviceEnv;
      }

      public String getServiceVersion() {
        return serviceVersion;
      }

      public String getLanguage() {
        return language;
      }
    }
  }
}
