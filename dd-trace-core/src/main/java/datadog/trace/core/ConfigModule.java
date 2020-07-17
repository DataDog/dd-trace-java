package datadog.trace.core;

import dagger.Module;
import dagger.Provides;
import datadog.trace.api.Config;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Named;
import lombok.Setter;

@Setter
@Module
class ConfigModule {

  private final Config config;
  private String serviceName;
  private String agentHost;
  private int agentPort;
  private String unixDomainSocketPath;
  private int agentTimeout;
  private int scopeDepthLimit;
  private boolean scopeStrictMode;
  private Map<String, String> localRootSpanTags;
  private Map<String, String> defaultSpanTags;
  private Map<String, String> serviceNameMappings;
  private Map<String, String> taggedHeaders;
  private int partialFlushMinSpans;

  ConfigModule() {
    this(Config.get());
  }

  public ConfigModule(final Config config) {
    this.config = config;
    serviceName = config.getServiceName();
    agentHost = config.getAgentHost();
    agentPort = config.getAgentPort();
    unixDomainSocketPath = config.getAgentUnixDomainSocket();
    agentTimeout = config.getAgentTimeout();
    scopeDepthLimit = config.getScopeDepthLimit();
    scopeStrictMode = config.isScopeStrictMode();
    localRootSpanTags = config.getLocalRootSpanTags();
    defaultSpanTags = config.getMergedSpanTags();
    serviceNameMappings = config.getServiceMapping();
    taggedHeaders = config.getHeaderTags();
    partialFlushMinSpans = config.getPartialFlushMinSpans();
  }

  @Provides
  Config config() {
    return config;
  }

  @Provides
  @Named("serviceName")
  String serviceName() {
    return serviceName;
  }

  @Provides
  @Named("agentHost")
  String agentHost() {
    return agentHost;
  }

  @Provides
  @Named("agentPort")
  int agentPort() {
    return agentPort;
  }

  @Provides
  @Nullable
  @Named("unixDomainSocketPath")
  String unixDomainSocketPath() {
    return unixDomainSocketPath;
  }

  @Provides
  @Named("agentTimeout")
  long agentTimeout() {
    return agentTimeout;
  }

  @Provides
  @Named("scopeDepthLimit")
  int scopeDepthLimit() {
    return scopeDepthLimit;
  }

  @Provides
  @Named("scopeStrictMode")
  boolean scopeStrictMode() {
    return scopeStrictMode;
  }

  @Provides
  @Named("localRootSpanTags")
  Map<String, String> localRootSpanTags() {
    return localRootSpanTags;
  }

  @Provides
  @Named("defaultSpanTags")
  Map<String, String> defaultSpanTags() {
    return defaultSpanTags;
  }

  @Provides
  @Named("serviceNameMappings")
  Map<String, String> serviceNameMappings() {
    return serviceNameMappings;
  }

  @Provides
  @Named("taggedHeaders")
  Map<String, String> taggedHeaders() {
    return taggedHeaders;
  }

  @Provides
  @Named("partialFlushMinSpans")
  int partialFlushMinSpans() {
    return partialFlushMinSpans;
  }
}
