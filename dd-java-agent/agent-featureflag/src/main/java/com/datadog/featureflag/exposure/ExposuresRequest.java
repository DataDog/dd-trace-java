package com.datadog.featureflag.exposure;

import java.util.List;
import java.util.Map;

public class ExposuresRequest {

  public final Map<String, String> context;
  public final List<ExposureEvent> events;

  public ExposuresRequest(final Map<String, String> context, final List<ExposureEvent> events) {
    this.context = context;
    this.events = events;
  }
}
