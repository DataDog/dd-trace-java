package com.datadog.featureflag;

import datadog.openfeature.internal.core.UfcParser;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;

/** Adapts the shared UFC parser to the Remote Configuration callback contract. */
final class UniversalFlagConfigParser implements ConfigurationDeserializer<ServerConfiguration> {

  static final UniversalFlagConfigParser INSTANCE = new UniversalFlagConfigParser();

  private final UfcParser parser = new UfcParser();

  private UniversalFlagConfigParser() {}

  @Override
  public ServerConfiguration deserialize(final byte[] content) throws IOException {
    return parser.parse(content);
  }
}
