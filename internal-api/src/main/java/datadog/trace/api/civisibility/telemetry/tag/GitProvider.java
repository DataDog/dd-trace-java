package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitProvider implements TagValue {
  USER_SUPPLIED_EXPECTED(Type.EXPECTED, Source.USER_SUPPLIED),
  USER_SUPPLIED_DISCREPANT(Type.DISCREPANT, Source.USER_SUPPLIED),

  CI_PROVIDER_EXPECTED(Type.EXPECTED, Source.CI_PROVIDER),
  CI_PROVIDER_DISCREPANT(Type.DISCREPANT, Source.CI_PROVIDER),

  LOCAL_GIT_EXPECTED(Type.EXPECTED, Source.LOCAL_GIT),
  LOCAL_GIT_DISCREPANT(Type.DISCREPANT, Source.LOCAL_GIT),

  GIT_CLIENT_EXPECTED(Type.EXPECTED, Source.GIT_CLIENT),
  GIT_CLIENT_DISCREPANT(Type.DISCREPANT, Source.GIT_CLIENT),

  EMBEDDED_EXPECTED(Type.EXPECTED, Source.EMBEDDED),
  EMBEDDED_DISCREPANT(Type.DISCREPANT, Source.EMBEDDED);

  private final Type type;
  private final Source source;

  GitProvider(Type type, Source source) {
    this.type = type;
    this.source = source;
  }

  @Override
  public String asString() {
    return type.getTag() + ":" + source.name().toLowerCase();
  }

  private enum Source {
    USER_SUPPLIED, CI_PROVIDER, LOCAL_GIT, GIT_CLIENT, EMBEDDED;
  }

  public enum Type {
    EXPECTED("expected_provider"),
    DISCREPANT("discrepant_provider");

    private final String tag;

    Type(String tag) {
      this.tag = tag;
    }

    public String getTag() {
      return tag;
    }
  }
}
