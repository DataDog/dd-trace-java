package com.datadog.profiling.jfr;

import java.util.function.Consumer;

/**
 * The main entry point to JFR writer functionality. Allows to define custom types and initiate
 * {@link JFRChunk chunks} for writing user events.
 */
public final class JFRWriter {
  private final Types types = new Types();

  public JFRWriter() {}

  /**
   * Start a new chunk.<br>
   * A chunk is a self-contained JFR data set which can stored to disk and read back by eg. Mission
   * Control
   *
   * @return a fresh new {@linkplain JFRChunk}
   */
  public JFRChunk newChunk() {
    types.resolveAll(); // first resolve all dangling resolvable types
    return new JFRChunk(types);
  }

  /**
   * Try registering a user event type with no additional attributes. If a same-named event already
   * exists it will be returned.
   *
   * @param name the event name
   * @return a user event type of the given name
   */
  public JFRType registerEventType(String name) {
    return registerEventType(name, builder -> {});
  }

  /**
   * Try registering a user event type. If a same-named event already exists it will be returned.
   *
   * @param name the event name
   * @param builderCallback will be called with the active {@linkplain CustomTypeBuilder} when the
   *     event is newly registered
   * @return a user event type of the given name
   */
  public JFRType registerEventType(String name, Consumer<CustomTypeBuilder> builderCallback) {
    return registerType(
        name,
        "jdk.jfr.Event",
        builder -> {
          builder
              .addField("stackTrace", Types.JDK.STACK_TRACE)
              .addField("eventThread", Types.JDK.THREAD)
              .addField("startTime", Types.Builtin.LONG, new JFRAnnotation(types.getType(Types.JDK.ANNOTATION_TIMESTAMP), "TICKS"));
          builderCallback.accept(builder);
        });
  }

  /**
   * Try registering a custom type. If a same-named type already exists it will be returned.
   *
   * @param name the type name
   * @param builderCallback will be called with the active {@linkplain CustomTypeBuilder} when the
   *     type is newly registered
   * @return a custom type of the given name
   */
  public JFRType registerType(String name, Consumer<CustomTypeBuilder> builderCallback) {
    return registerType(name, null, builderCallback);
  }

  /**
   * Try registering a custom type. If a same-named type already exists it will be returned.
   *
   * @param name the type name
   * @param supertype the super type name
   * @param builderCallback will be called with the active {@linkplain CustomTypeBuilder} when the
   *     type is newly registered
   * @return a custom type of the given name
   */
  public JFRType registerType(
      String name, String supertype, Consumer<CustomTypeBuilder> builderCallback) {
    return types.getOrAdd(name, supertype, builderCallback);
  }

  /**
   * A convenience method to easily get to JDK registered custom types in type-safe manner.
   *
   * @param type the type
   * @return the previously registered JDK type
   * @throws IllegalArgumentException if an attempt to retrieve non-registered JDK type is made
   */
  public JFRType getType(Types.JDK type) {
    return getType(type.getTypeName());
  }

  /**
   * Try retrieving a previously registered custom type.
   *
   * @param typeName the type name
   * @return the previously registered custom type
   * @throws IllegalArgumentException if an attempt to retrieve non-registered custom type is made
   */
  public JFRType getType(String typeName) {
    JFRType type = types.getType(typeName);
    if (type == null) {
      throw new IllegalArgumentException();
    }
    return type;
  }
}
