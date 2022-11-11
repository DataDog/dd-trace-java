package datadog.telemetry;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.function.Supplier;

class RequestBuilderSupplier implements Supplier<RequestBuilder> {
  private final SharedCommunicationObjects sco;
  private RequestBuilder requestBuilder;

  RequestBuilderSupplier(SharedCommunicationObjects sco) {
    this.sco = sco;
  }

  @Override
  public RequestBuilder get() {
    if (requestBuilder == null) {
      requestBuilder = new RequestBuilder(sco);
    }
    return requestBuilder;
  }
}
