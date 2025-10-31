package datadog.trace.api.featureflag;

public interface FeatureFlagConfigListener {

  void onConfigurationChanged(FeatureFlagConfig config);
}
