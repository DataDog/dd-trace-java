package datadog.trace.api.openfeature.exposure;

import datadog.trace.api.openfeature.exposure.dto.ExposureEvent;

public interface ExposureListener {

  void onExposure(ExposureEvent event);
}
