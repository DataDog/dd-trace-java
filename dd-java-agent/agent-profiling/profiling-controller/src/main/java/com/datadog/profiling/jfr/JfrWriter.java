package com.datadog.profiling.jfr;

import java.util.function.Consumer;

public final class JfrWriter {
  private final Types types = new Types();

  public JfrWriter() {}

  public JfrChunkWriter newChunk() {
    return new JfrChunkWriter(types);
  }

  public Type registerEventType(String name) {
    return registerEventType(name, builder -> {});
  }

  public Type registerEventType(String name, Consumer<CustomTypeBuilder> buildWith) {
    return registerType(
        name,
        "jdk.jfr.Event",
        builder -> {
          builder
              .addField("stackTrace", Types.JDK.STACK_TRACE)
              .addField("eventThread", Types.JDK.THREAD)
              .addField("startTime", Types.JDK.TICKS);
          buildWith.accept(builder);
        });
  }

  public Type registerType(String name, Consumer<CustomTypeBuilder> buildWith) {
    return registerType(name, null, buildWith);
  }

  public Type registerType(String name, String supertype, Consumer<CustomTypeBuilder> buildWith) {
    return types.getOrAdd(name, supertype, buildWith);
  }

  public Type getJdkType(Types.JDK type) {
    return getJdkType(type.getTypeName());
  }

  public Type getJdkType(String typeName) {
    Type type = types.getType(typeName);
    if (type == null) {
      throw new IllegalArgumentException();
    }
    return type;
  }
}
