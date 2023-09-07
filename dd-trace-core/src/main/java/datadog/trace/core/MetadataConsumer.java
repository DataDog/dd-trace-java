package datadog.trace.core;

import java.util.function.Consumer;

@FunctionalInterface
public interface MetadataConsumer extends Consumer<Metadata> {

  MetadataConsumer NO_OP = (md) -> {};

  void accept(Metadata metadata);
}
