package datadog.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/** Direct intake composition without agent discovery or Remote Configuration dependencies. */
public final class DirectIntakeApiFactory {
  private final Config config;
  private final OkHttpClient httpClient;

  public DirectIntakeApiFactory(Config config, OkHttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
  }

  public BackendApi create(Intake intake, boolean responseCompression, boolean followRedirects) {
    HttpUrl agentlessUrl = buildDirectIntakeUrl(intake, config);
    String apiKey = config.getApiKey();
    if (apiKey == null || apiKey.isEmpty()) {
      throw new FatalAgentMisconfigurationError(
          "Agentless mode is enabled and API key is not set. Please set DD_API_KEY");
    }
    String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
    return new IntakeApi(
        agentlessUrl,
        apiKey,
        traceId,
        retryPolicyFactory(),
        directIntakeHttpClient(httpClient, followRedirects),
        responseCompression);
  }

  static OkHttpClient directIntakeHttpClient(
      final OkHttpClient intakeHttpClient, final boolean followRedirects) {
    if (followRedirects) {
      return intakeHttpClient;
    }
    return intakeHttpClient.newBuilder().followRedirects(false).build();
  }

  private static HttpUrl buildDirectIntakeUrl(Intake intake, Config config) {
    if (intake != Intake.EVENT_PLATFORM) {
      return HttpUrl.get(intake.getAgentlessUrl(config));
    }
    return buildEventPlatformIntakeUrl(config.getSite());
  }

  static HttpUrl buildEventPlatformIntakeUrl(String site) {
    if (site == null || site.isEmpty()) {
      throw new IllegalArgumentException("Invalid Datadog site");
    }

    String expectedHost = Intake.EVENT_PLATFORM.getUrlPrefix() + "." + site;
    HttpUrl url =
        new HttpUrl.Builder()
            .scheme("https")
            .host(expectedHost)
            .addPathSegment("api")
            .addPathSegment(Intake.EVENT_PLATFORM.getVersion())
            .addPathSegment("")
            .build();
    if (!url.host().equalsIgnoreCase(expectedHost)) {
      throw new IllegalArgumentException("Invalid Datadog site");
    }
    return url;
  }

  private static HttpRetryPolicy.Factory retryPolicyFactory() {
    return new HttpRetryPolicy.Factory(5, 100, 2.0, true);
  }
}
