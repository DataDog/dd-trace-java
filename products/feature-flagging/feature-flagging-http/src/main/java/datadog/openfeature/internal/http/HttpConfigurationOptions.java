package datadog.openfeature.internal.http;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Immutable options for the provider-owned CDN configuration source. */
public final class HttpConfigurationOptions {

  public final URI endpoint;
  public final Duration pollInterval;
  public final Duration requestTimeout;
  public final String apiKey;
  public final boolean managedEndpoint;

  private HttpConfigurationOptions(final Builder builder) {
    endpoint = Objects.requireNonNull(builder.endpoint, "endpoint");
    pollInterval = positive(builder.pollInterval, "pollInterval");
    requestTimeout = positive(builder.requestTimeout, "requestTimeout");
    apiKey = builder.apiKey;
    managedEndpoint = builder.managedEndpoint;
  }

  public static Builder builder() {
    return new Builder();
  }

  private static Duration positive(final Duration value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  public static final class Builder {
    private URI endpoint;
    private Duration pollInterval = Duration.ofSeconds(30);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private String apiKey;
    private boolean managedEndpoint;

    public Builder endpoint(final URI endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder pollInterval(final Duration pollInterval) {
      this.pollInterval = pollInterval;
      return this;
    }

    public Builder requestTimeout(final Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    public Builder apiKey(final String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder managedEndpoint(final boolean managedEndpoint) {
      this.managedEndpoint = managedEndpoint;
      return this;
    }

    public HttpConfigurationOptions build() {
      return new HttpConfigurationOptions(this);
    }
  }
}
