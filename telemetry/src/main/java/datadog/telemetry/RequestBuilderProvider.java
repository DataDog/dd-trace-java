package datadog.telemetry;

import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Platform;
import okhttp3.HttpUrl;

public class RequestBuilderProvider {
  public static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  private static final String API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";

  final HttpUrl httpUrl;
  final boolean debug;
  final String runtimeId;
  final String env;
  final String serviceName;
  final String serviceVersion;
  final String tracerVersion;
  final String languageName;
  final String languageVersion;
  final String runtimeName;
  final String runtimeVersion;
  final String runtimePatches;
  final String hostname;
  final String os;
  final String osVersion;
  final String kernelName;
  final String kernelRelease;
  final String kernelVersion;
  final String architecture;

  public RequestBuilderProvider(HttpUrl agentUrl) {
    this.httpUrl = agentUrl.newBuilder().addPathSegments(API_ENDPOINT).build();

    this.debug = false; // TODO get from config? or pass as a param

    Config config = Config.get();

    this.runtimeId = config.getRuntimeId();
    this.env = config.getEnv();
    this.serviceName = config.getServiceName();
    this.serviceVersion = config.getVersion();
    this.tracerVersion = TracerVersion.TRACER_VERSION;
    this.languageName = DDTags.LANGUAGE_TAG_VALUE;
    this.languageVersion = Platform.getLangVersion();
    this.runtimeName = Platform.getRuntimeVendor();
    this.runtimeVersion = Platform.getRuntimeVersion();
    this.runtimePatches = Platform.getRuntimePatches();

    this.hostname = HostInfo.getHostname();
    this.os = HostInfo.getOsName();
    this.osVersion = HostInfo.getOsVersion();
    this.kernelName = HostInfo.getKernelName();
    this.kernelRelease = HostInfo.getKernelRelease();
    this.kernelVersion = HostInfo.getKernelVersion();
    this.architecture = HostInfo.getArchitecture();
  }

  public RequestBuilder create(RequestType requestType) {
    return new RequestBuilder(this, requestType);
  }
}
