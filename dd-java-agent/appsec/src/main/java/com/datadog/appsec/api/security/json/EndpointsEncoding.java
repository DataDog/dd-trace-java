package com.datadog.appsec.api.security.json;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.appsec.api.security.model.Endpoint;
import java.util.List;

public class EndpointsEncoding {

  private static final JsonAdapter<Endpoints> JSON_ADAPTER =
      new Moshi.Builder().add(new EndpointAdapter()).build().adapter(Endpoints.class);

  public static String toJson(final List<Endpoint> endpoints) {
    final Endpoints target = new Endpoints();
    target.setEndpoints(endpoints);
    return JSON_ADAPTER.toJson(target);
  }

  public static class Endpoints {

    private List<Endpoint> endpoints;

    public List<Endpoint> getEndpoints() {
      return endpoints;
    }

    public void setEndpoints(final List<Endpoint> endpoints) {
      this.endpoints = endpoints;
    }
  }
}
