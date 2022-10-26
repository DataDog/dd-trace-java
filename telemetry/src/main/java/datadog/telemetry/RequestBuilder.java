package datadog.telemetry;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.http.SafeRequestBuilder;
import datadog.telemetry.api.ApiVersion;
import datadog.telemetry.api.Application;
import datadog.telemetry.api.Host;
import datadog.telemetry.api.Log;
import datadog.telemetry.api.LogTelemetry;
import datadog.telemetry.api.Payload;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.api.Telemetry;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestBuilder {

  private static final String API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";
  private static final String STAGING_API_ENDPOINT = "api/v2/apmtelemetry";

  private static final ApiVersion API_VERSION = ApiVersion.V1;
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private static final Logger log = LoggerFactory.getLogger(RequestBuilder.class);

  private static final JsonAdapter<Telemetry> JSON_ADAPTER =
      new Moshi.Builder()
          .add(new PolymorphicAdapterFactory(Payload.class))
          .build()
          .adapter(Telemetry.class);

private static final JsonAdapter<LogTelemetry> LOG_JSON_ADAPTER =
      new Moshi.Builder()
          .add(new PolymorphicAdapterFactory(Payload.class))
          .build()
          .adapter(LogTelemetry.class);

  private static final AtomicLong SEQ_ID = new AtomicLong();

  private final HttpUrl httpUrl;
  private final Application application;
  private final Host host;
  private final String runtimeId;

  public RequestBuilder(HttpUrl httpUrl) {
    // if (Config.get().isTelemetryDebugEnabled()) { 

    //   this.httpUrl= new HttpUrl.Builder()  
    //       .scheme("https")
    //       .host("all-http-intake.logs.datad0g.com")
    //       .addPathSegments(STAGING_API_ENDPOINT)
    //       .build();
      
    //   System.out.println("Staging Endpoint: " + this.httpUrl.toString());
    // }
    // else { 
    //   this.httpUrl = httpUrl.newBuilder().addPathSegments(API_ENDPOINT).build();
    // }

    this.httpUrl = httpUrl.newBuilder().addPathSegments(API_ENDPOINT).build();

    Config config = Config.get();

    this.runtimeId = config.getRuntimeId();
    this.application =
        new Application()
            .env(config.getEnv())
            .serviceName(config.getServiceName())
            .serviceVersion(config.getVersion())
            .tracerVersion(TracerVersion.TRACER_VERSION)
            .languageName("jvm")
            .languageVersion(Platform.getLangVersion())
            .runtimeName(Platform.getRuntimeVendor())
            .runtimeVersion(Platform.getRuntimeVersion())
            .runtimePatches(Platform.getRuntimePatches());

    ContainerInfo containerInfo = ContainerInfo.get();
    this.host =
        new Host()
            .hostname(HostInfo.getHostname())
            .os(HostInfo.getOsName())
            .osVersion(HostInfo.getOsVersion())
            .kernelName(HostInfo.getKernelName())
            .kernelRelease(HostInfo.getKernelRelease())
            .kernelVersion(HostInfo.getKernelVersion())
            .containerId(containerInfo.getContainerId());
  }

 

  // Special Telemetry Log Payload - no headers for payload request type
  public Request logBuild(RequestType requestType, List<Log>payload) {
    LogTelemetry telemetry =
        new LogTelemetry()
            .apiVersion(API_VERSION)
            .requestType(requestType)
            .tracerTime(System.currentTimeMillis() / 1000L)
            .runtimeId(runtimeId)
            .seqId(SEQ_ID.incrementAndGet())
            .application(application)
            .host(host)
            .payload(payload)
            .debug(Config.get().isTelemetryDebugEnabled());

    String json = LOG_JSON_ADAPTER.toJson(telemetry);
    RequestBody body = RequestBody.create(JSON, json);

    Request hi = new SafeRequestBuilder()
        .url(httpUrl)
        .addHeader("User-Agent","")
        .addHeader("Content-Type", JSON.toString())
        .addHeader("DD-API-KEY","YOUR-DD-API-KEY-HERE") 
        .addHeader("DD-Telemetry-Request-Type", requestType.toString())
        .addHeader("DD-Telemetry-API-Version", API_VERSION.toString())
        .post(body)
        .build();

    System.out.println("This is complete request: " + hi);
    return hi;
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
            .payload(payload)
            .debug(Config.get().isTelemetryDebugEnabled());

    String json = JSON_ADAPTER.toJson(telemetry);
    RequestBody body = RequestBody.create(JSON, json);

    return new SafeRequestBuilder()
        .url(httpUrl)
        .addHeader("Content-Type", JSON.toString())
        .addHeader("DD-Telemetry-API-Version", API_VERSION.toString())
        .addHeader("DD-Telemetry-Request-Type", requestType.toString())
        .post(body)
        .build();
  }
}
