package com.datadog.debugger.agent;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Okio;

public class DebuggerFeaturesDeserializer implements ConfigurationDeserializer<DebuggerFeatures> {
  public static final DebuggerFeaturesDeserializer INSTANCE = new DebuggerFeaturesDeserializer();

  private static final JsonAdapter<DebuggerFeatures> ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(DebuggerFeatures.class);

  private DebuggerFeaturesDeserializer() {}

  @Override
  public DebuggerFeatures deserialize(byte[] content) throws IOException {
    return ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }

  /* for testing */
  public String serialize(DebuggerFeatures features) {
    return ADAPTER.toJson(features);
  }
}
