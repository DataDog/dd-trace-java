package datadog.telemetry;

import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Platform;
import okhttp3.HttpUrl;

public class RequestBuilderProvider {
  private static final String API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";

  final HttpUrl httpUrl;
  final boolean debug;

  enum CommonData {
    INSTANCE;

    final String runtimeId = Config.get().getRuntimeId();
    final String env = Config.get().getEnv();
    final String serviceName = Config.get().getServiceName();
    final String serviceVersion = Config.get().getVersion();
    final String tracerVersion = TracerVersion.TRACER_VERSION;
    final String languageName = DDTags.LANGUAGE_TAG_VALUE;
    final String languageVersion = Platform.getLangVersion();
    final String runtimeName = Platform.getRuntimeVendor();
    final String runtimeVersion = Platform.getRuntimeVersion();
    final String runtimePatches = Platform.getRuntimePatches();

    final String hostname = HostInfo.getHostname();
    final String os = HostInfo.getOsName();
    final String osVersion = HostInfo.getOsVersion();
    final String kernelName = HostInfo.getKernelName();
    final String kernelRelease = HostInfo.getKernelRelease();
    final String kernelVersion = HostInfo.getKernelVersion();
    final String architecture = HostInfo.getArchitecture();
  }

  public RequestBuilderProvider(HttpUrl agentUrl) {
    this.httpUrl = agentUrl.newBuilder().addPathSegments(API_ENDPOINT).build();

    // TODO It was hardcoded ad disabled. Should it be taken from the config?
    this.debug = false;
  }

  public RequestBuilder create(RequestType requestType) {
    return new RequestBuilder(this, requestType);
  }
}
