package datadog.trace.api.naming;

import datadog.trace.api.Config;
import datadog.trace.api.naming.v0.NamingSchemaV0;
import datadog.trace.api.naming.v1.NamingSchemaV1;
import javax.annotation.Nonnull;

/** This is the main entry point to drive span naming decisions. */
public class SpanNaming {
  public static final int SCHEMA_MIN_VERSION = 0;
  public static final int SCHEMA_MAX_VERSION = 1;

  private static class Singleton {
    private static SpanNaming INSTANCE = new SpanNaming();
  }

  public static SpanNaming instance() {
    return Singleton.INSTANCE;
  }

  private final NamingSchema namingSchema;
  private final int version;

  // Choice of version will be driven by a configuration parameter when all the instrumentations'
  // naming will be addressed
  private SpanNaming() {
    this(Config.get().getSpanAttributeSchemaVersion());
  }

  private SpanNaming(final int version) {
    this.version = version;
    switch (version) {
      case 1:
        namingSchema = new NamingSchemaV1();
        break;
      default:
        namingSchema = new NamingSchemaV0();
        break;
    }
  }

  @Nonnull
  public NamingSchema namingSchema() {
    return namingSchema;
  }

  public int version() {
    return version;
  }
}
