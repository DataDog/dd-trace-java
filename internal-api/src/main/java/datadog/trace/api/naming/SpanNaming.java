package datadog.trace.api.naming;

import datadog.trace.api.Config;
import datadog.trace.api.naming.v0.NamingSchemaV0;
import datadog.trace.api.naming.v1.NamingSchemaV1;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** This is the main entry point to drive span naming decisions. */
public class SpanNaming {
  public static final int SCHEMA_MIN_VERSION = 0;
  public static final int SCHEMA_MAX_VERSION = 1;

  private static class Singleton {
    private static SpanNaming INSTANCE = new SpanNaming();
  }

  public static class ForLocalRoot {
    private final boolean mutable;
    private volatile String preferredServiceName;

    public ForLocalRoot(final Config config) {
      this.mutable = !config.isServiceNameSetByUser();
    }

    @Nullable
    public String getPreferredServiceName() {
      return preferredServiceName;
    }

    public boolean maybeOverrideServiceName(final String serviceName) {
      if (mutable) {
        preferredServiceName = serviceName;
      }
      return mutable;
    }
  }

  public static SpanNaming instance() {
    return Singleton.INSTANCE;
  }

  private final NamingSchema namingSchema;
  private final int version;

  private final ForLocalRoot localRootNaming;

  private SpanNaming() {
    this.version = Config.get().getSpanAttributeSchemaVersion();
    switch (version) {
      case 1:
        namingSchema = new NamingSchemaV1();
        break;
      default:
        namingSchema = new NamingSchemaV0();
        break;
    }
    this.localRootNaming = new ForLocalRoot(Config.get());
  }

  @Nonnull
  public NamingSchema namingSchema() {
    return namingSchema;
  }

  @Nonnull
  public ForLocalRoot localRoot() {
    return localRootNaming;
  }

  public int version() {
    return version;
  }
}
