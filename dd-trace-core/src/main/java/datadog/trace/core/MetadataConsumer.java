package datadog.trace.core;

public abstract class MetadataConsumer {
  public abstract void accept(Metadata metadata);
}
