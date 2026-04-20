package datadog.trace.api.featureflag.exposure;

import java.util.List;
import java.util.Map;

public class ExposuresRequest {

  public final Map<String, String> context;
  public final List<ExposureEvent> exposures;

  public ExposuresRequest(final Map<String, String> context, final List<ExposureEvent> exposures) {
    this.context = context;
    this.exposures = exposures;
  }
}
