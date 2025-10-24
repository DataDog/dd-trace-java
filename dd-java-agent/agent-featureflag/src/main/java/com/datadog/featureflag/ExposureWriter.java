package com.datadog.featureflag;

import datadog.trace.api.featureflag.FeatureFlagEvaluator.Context;
import datadog.trace.api.featureflag.FeatureFlagEvaluator.Resolution;

public interface ExposureWriter extends AutoCloseable {

  void init();

  void close();

  void write(String flag, Context context, Resolution<?> resolution);
}
