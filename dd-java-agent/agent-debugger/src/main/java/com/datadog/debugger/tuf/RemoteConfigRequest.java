package com.datadog.debugger.tuf;

import com.datadog.debugger.agent.DebuggerAgent;
import com.squareup.moshi.Json;
import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Handles requests to Remote Configuration */
public class RemoteConfigRequest {

  public static RemoteConfigRequest newRequest(
      String clientId,
      String runtimeId,
      String tracerVersion,
      String serviceName,
      String serviceEnv,
      String serviceVersion) {
    ClientInfo.TracerInfo tracerInfo =
        new RemoteConfigRequest.ClientInfo.TracerInfo(
            runtimeId, tracerVersion, serviceName, serviceEnv, serviceVersion);
    ClientInfo.ClientState state = new ClientInfo.ClientState(0, null, null, null);
    ClientInfo clientInfo = new RemoteConfigRequest.ClientInfo(clientId, tracerInfo, state);
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
    private final ClientState state;

    @Json(name = "client_tracer")
    private final TracerInfo tracerInfo;

    @Json(name = "is_tracer")
    private final Boolean isTracer = true;

    public ClientInfo(String id, TracerInfo tracerInfo, ClientState clientState) {
      this.id = id;
      this.tracerInfo = tracerInfo;
      this.state = clientState;
    }

    public String getId() {
      return id;
    }

    public TracerInfo getTracerClient() {
      return tracerInfo;
    }

    public String getName() {
      return name;
    }

    public List<String> getProducts() {
      return products;
    }

    public ClientState getState() {
      return state;
    }

    public static class ClientState {
      @Json(name = "root_version")
      private final int rootVersion = 1;

      @Json(name = "targets_version")
      private final int targetsVersion;

      @Json(name = "config_states")
      private final List<ConfigState> configStates;

      @Json(name = "has_error")
      private final boolean hasError;

      @Json(name = "error")
      private final String error;

      @Json(name = "backend_client_state")
      private final String backendClientState;

      public ClientState(
          int targetsVersion,
          List<ConfigState> configStates,
          String error,
          String backendClientState) {
        this.targetsVersion = targetsVersion;
        this.configStates = configStates;
        this.error = error;
        this.hasError = error != null;
        this.backendClientState = backendClientState;
      }

      public int getRootVersion() {
        return rootVersion;
      }

      public int getTargetsVersion() {
        return targetsVersion;
      }

      public List<ConfigState> getConfigStates() {
        return configStates;
      }

      public boolean getHasError() {
        return hasError;
      }

      public String getError() {
        return error;
      }

      public String getBackendClientState() {
        return backendClientState;
      }
    }

    public static class ConfigState {

      @Json(name = "id")
      private final String id;

      @Json(name = "version")
      private final int version;

      @Json(name = "product")
      private final String product;

      public ConfigState(String id, int version, String product) {
        this.id = id;
        this.version = version;
        this.product = product;
      }

      public String getId() {
        return id;
      }

      public int getVersion() {
        return version;
      }

      public String getProduct() {
        return product;
      }
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

      private final String language = "java";

      private final List<String> tags;

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
        List<String> tags =
            Config.get().getGlobalTags().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.toList());
        tags.addAll(
            Arrays.asList(
                "env:" + Config.get().getEnv(),
                "version:" + Config.get().getVersion(),
                "tracer_version:" + TracerVersion.TRACER_VERSION,
                "agent_version:" + DebuggerAgent.getAgentVersion(),
                "host_name:" + Config.getHostName()));
        this.tags = tags;
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
