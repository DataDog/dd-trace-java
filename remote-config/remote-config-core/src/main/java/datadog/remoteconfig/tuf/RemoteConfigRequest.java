package datadog.remoteconfig.tuf;

import com.squareup.moshi.Json;
import datadog.trace.api.ProcessTags;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Handles requests to Remote Configuration */
public class RemoteConfigRequest {

  public static RemoteConfigRequest newRequest(
      String clientId,
      String runtimeId,
      String tracerVersion,
      Collection<String> productNames,
      String serviceName,
      List<String> extraServices,
      String serviceEnv,
      String serviceVersion,
      List<String> tags,
      ClientInfo.ClientState clientState,
      Collection<CachedTargetFile> cachedTargetFiles,
      long capabilities) {

    ClientInfo.TracerInfo tracerInfo =
        new RemoteConfigRequest.ClientInfo.TracerInfo(
            runtimeId,
            tracerVersion,
            serviceName,
            extraServices,
            serviceEnv,
            serviceVersion,
            tags,
            ProcessTags.getTagsAsList());

    ClientInfo clientInfo =
        new RemoteConfigRequest.ClientInfo(
            clientState, clientId, productNames, tracerInfo, capabilities);

    return new RemoteConfigRequest(clientInfo, cachedTargetFiles);
  }

  private final ClientInfo client;

  @Json(name = "cached_target_files")
  private final Collection<CachedTargetFile> cachedTargetFiles;

  public RemoteConfigRequest(ClientInfo client, Collection<CachedTargetFile> cachedTargetFiles) {
    this.client = client;
    // system tests expect this to be null if we're not holding anything cached
    this.cachedTargetFiles =
        (cachedTargetFiles != null && cachedTargetFiles.isEmpty()) ? null : cachedTargetFiles;
  }

  public ClientInfo getClient() {
    return this.client;
  }

  /** Stores client information for Remote Configuration */
  public static class ClientInfo {
    @Json(name = "state")
    private final ClientState clientState;

    private final String id;
    private final Collection<String> products;

    @Json(name = "client_tracer")
    private final TracerInfo tracerInfo;

    @Json(name = "client_agent")
    private final AgentInfo agentInfo = null; // MUST NOT be set

    @Json(name = "is_tracer")
    private final boolean isTracer = true;

    @Json(name = "is_agent")
    private final Boolean isAgent = null; // MUST NOT be set;

    private final byte[] capabilities;

    public ClientInfo(
        ClientState clientState,
        String id,
        Collection<String> productNames,
        TracerInfo tracerInfo,
        final long capabilities) {
      this.clientState = clientState;
      this.id = id;
      this.products = productNames;
      this.tracerInfo = tracerInfo;

      // Big-endian encoding of the `long` capabilities, stripping any trailing zero bytes
      // (except the first one)
      final int size = Math.max(1, Long.BYTES - Long.numberOfLeadingZeros(capabilities) / 8);
      this.capabilities = new byte[size];
      for (int i = size - 1; i >= 0; i--) {
        this.capabilities[size - i - 1] = (byte) (capabilities >>> (i * 8));
      }
    }

    public TracerInfo getTracerInfo() {
      return this.tracerInfo;
    }

    public static class ClientState {
      @Json(name = "root_version")
      public long rootVersion = 1L;

      @Json(name = "targets_version")
      public long targetsVersion;

      @Json(name = "config_states")
      public List<ConfigState> configStates = new ArrayList<>();

      @Json(name = "has_error")
      public boolean hasError;

      public String error;

      @Json(name = "backend_client_state")
      public String backendClientState;

      public void setState(
          long targetsVersion,
          List<ConfigState> configStates,
          String error,
          String backendClientState) {
        this.targetsVersion = targetsVersion;
        this.configStates = configStates;
        this.error = error;
        this.hasError = error != null && !error.isEmpty();
        this.backendClientState = backendClientState;
      }

      public static class ConfigState {
        public static final int APPLY_STATE_ACKNOWLEDGED = 2;
        public static final int APPLY_STATE_ERROR = 3;

        private String id;
        private long version;
        public String product;

        @Json(name = "apply_state")
        public int applyState;

        @Json(name = "apply_error")
        public String applyError;

        public void setState(String id, long version, String product, String error) {
          this.id = id;
          this.version = version;
          this.product = product;
          this.applyState = error == null ? APPLY_STATE_ACKNOWLEDGED : APPLY_STATE_ERROR;
          this.applyError = error;
        }
      }
    }

    public static class TracerInfo {
      @Json(name = "runtime_id")
      private final String runtimeId;

      private final String language = "java";

      private final List<String> tags;

      @Json(name = "tracer_version")
      private final String tracerVersion;

      @Json(name = "service")
      private final String serviceName;

      @Json(name = "extra_services")
      private final List<String> extraServices;

      @Json(name = "env")
      private final String serviceEnv;

      @Json(name = "app_version")
      private final String serviceVersion;

      @Json(name = "process_tags")
      private final List<String> processTags;

      public TracerInfo(
          String runtimeId,
          String tracerVersion,
          String serviceName,
          List<String> extraServices,
          String serviceEnv,
          String serviceVersion,
          List<String> tags,
          List<String> processTags) {
        this.runtimeId = runtimeId;
        this.tracerVersion = tracerVersion;
        this.serviceName = serviceName;
        this.extraServices = extraServices;
        this.serviceEnv = serviceEnv;
        this.serviceVersion = serviceVersion;
        this.tags = tags;
        this.processTags = processTags;
      }

      public String getServiceName() {
        return this.serviceName;
      }

      public List<String> getExtraServices() {
        return extraServices;
      }

      public String getServiceEnv() {
        return this.serviceEnv;
      }

      public String getServiceVersion() {
        return this.serviceVersion;
      }

      public List<String> getTags() {
        return tags;
      }

      public List<String> getProcessTags() {
        return processTags;
      }
    }

    private static class AgentInfo {
      private final String name;
      private final String version;

      private AgentInfo(String name, String version) {
        this.name = name;
        this.version = version;
      }
    }
  }

  public static class CachedTargetFile {
    public final String path;
    public final long length;
    public final List<TargetFileHash> hashes;

    public CachedTargetFile(
        String path, long length, Map<String /*algo*/, String /*digest*/> hashes) {
      this.path = path;
      this.length = length;
      List<TargetFileHash> hashesList =
          hashes.entrySet().stream()
              .map(e -> new TargetFileHash(e.getKey(), e.getValue()))
              .collect(Collectors.toList());
      this.hashes = hashesList;
    }

    public boolean hashesMatch(Map<String /*algo*/, String /*digest*/> hashesMap) {
      if (this.hashes == null) {
        return false;
      }

      if (hashesMap.size() != this.hashes.size()) {
        return false;
      }

      for (TargetFileHash tfh : hashes) {
        String digest = hashesMap.get(tfh.algorithm);
        if (!digest.equals(tfh.hash)) {
          return false;
        }
      }

      return true;
    }

    public static class TargetFileHash {
      public final String algorithm;
      public final String hash;

      public TargetFileHash(String algorithm, String hash) {
        this.algorithm = algorithm;
        this.hash = hash;
      }
    }
  }
}
