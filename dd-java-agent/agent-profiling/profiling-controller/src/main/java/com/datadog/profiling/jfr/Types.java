package com.datadog.profiling.jfr;

import static com.datadog.profiling.jfr.Annotation.ANNOTATION_SUPER_TYPE_NAME;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** An access class for various {@linkplain Type} related operations */
public final class Types {
  /** A {@link Type type} predefined by the writer */
  interface Predefined extends NamedType {}

  /** Built-in types */
  public enum Builtin implements Predefined {
    BYTE("byte", byte.class),
    CHAR("char", char.class),
    SHORT("short", short.class),
    INT("int", int.class),
    LONG("long", long.class),
    FLOAT("float", float.class),
    DOUBLE("double", double.class),
    BOOLEAN("boolean", boolean.class),
    STRING("java.lang.String", String.class);

    private static Map<String, Builtin> NAME_MAP;
    private final String typeName;
    private final Class<?> type;

    Builtin(String name, Class<?> type) {
      addName(name);
      this.typeName = name;
      this.type = type;
    }

    private static Map<String, Builtin> getNameMap() {
      if (NAME_MAP == null) {
        NAME_MAP = new HashMap<>();
      }
      return NAME_MAP;
    }

    private void addName(String name) {
      getNameMap().put(name, this);
    }

    public static boolean hasType(String name) {
      return getNameMap().containsKey(name);
    }

    public static Builtin ofName(String name) {
      return getNameMap().get(name);
    }

    public static Builtin ofType(Type type) {
      return ofName(type.getTypeName());
    }

    @Override
    public String getTypeName() {
      return typeName;
    }

    public Class<?> getTypeClass() {
      return type;
    }
  }

  /** Types (subset of) defined by the JFR JVM implementation */
  public enum JDK implements Predefined {
    TICKSPAN("jdk.type.Tickspan"),
    TICKS("jdk.type.Ticks"),
    THREAD_GROUP("jdk.types.ThreadGroup"),
    THREAD("java.lang.Thread"),
    STACK_TRACE("jdk.types.StackTrace"),
    STACK_FRAME("jdk.types.StackFrame"),
    METHOD("jdk.types.Method"),
    FRAME_TYPE("jdk.types.FrameType"),
    CLASS("java.lang.Class"),
    SYMBOL("jdk.types.Symbol"),
    CLASS_LOADER("jdk.type.ClassLoader"),
    PACKAGE("jdk.types.Package"),
    MODULE("jdk.types.Module"),
    ANNOTATION_LABEL("jdk.jfr.Label"),
    ANNOTATION_CONTENT_TYPE("jdk.jfr.ContentType"),
    ANNOTATION_NAME("jdk.jfr.Name"),
    ANNOTATION_DESCRIPTION("jdk.jfr.Description"),
    ANNOTATION_TIMESTAMP("jdk.jfr.Timestamp"),
    ANNOTATION_TIMESPAN("jdk.jfr.Timespan"),
    ANNOTATION_UNSIGNED("jdk.jfr.Unsigned");

    private final String typeName;

    JDK(String name) {
      this.typeName = name;
    }

    @Override
    public String getTypeName() {
      return typeName;
    }
  }

  private final Metadata metadata;

  Types(Metadata metadata) {
    this.metadata = metadata;

    registerBuiltins();
    registerJdkTypes();
    this.metadata.resolveTypes(); // resolve all back-referenced types
  }

  private void registerBuiltins() {
    metadata.registerBuiltin(Builtin.BYTE);
    metadata.registerBuiltin(Builtin.CHAR);
    metadata.registerBuiltin(Builtin.SHORT);
    metadata.registerBuiltin(Builtin.INT);
    metadata.registerBuiltin(Builtin.LONG);
    metadata.registerBuiltin(Builtin.FLOAT);
    metadata.registerBuiltin(Builtin.DOUBLE);
    metadata.registerBuiltin(Builtin.BOOLEAN);
    metadata.registerBuiltin(Builtin.STRING);
  }

  private void registerJdkTypes() {
    Type annotationNameType =
        getOrAdd(
            JDK.ANNOTATION_NAME,
            ANNOTATION_SUPER_TYPE_NAME,
            builder -> {
              builder.addField("value", Builtin.STRING);
            });
    Type annotationLabelType =
        getOrAdd(
            JDK.ANNOTATION_LABEL,
            ANNOTATION_SUPER_TYPE_NAME,
            builder -> {
              builder.addField("value", Builtin.STRING);
            });
    Type annotationDescriptionType =
        getOrAdd(
            JDK.ANNOTATION_DESCRIPTION,
            ANNOTATION_SUPER_TYPE_NAME,
            builder -> {
              builder.addField("value", Builtin.STRING);
            });
    Type annotationContentTypeType =
        getOrAdd(JDK.ANNOTATION_CONTENT_TYPE, ANNOTATION_SUPER_TYPE_NAME, builder -> {});
    getOrAdd(
        JDK.ANNOTATION_TIMESTAMP,
        ANNOTATION_SUPER_TYPE_NAME,
        builder -> {
          builder
              .addField("value", Builtin.STRING)
              .addAnnotation(annotationNameType, "jdk.jfr.Timestamp")
              .addAnnotation(annotationContentTypeType, null)
              .addAnnotation(annotationLabelType, "Timestamp")
              .addAnnotation(annotationDescriptionType, "A point in time");
        });
    getOrAdd(
        JDK.ANNOTATION_TIMESPAN,
        ANNOTATION_SUPER_TYPE_NAME,
        builder -> {
          builder
              .addField("value", Builtin.STRING)
              .addAnnotation(annotationNameType, "jdk.jfr.Timespan")
              .addAnnotation(annotationContentTypeType, null)
              .addAnnotation(annotationLabelType, "Timespan")
              .addAnnotation(
                  annotationDescriptionType, "A duration, measured in nanoseconds by default");
        });
    getOrAdd(
        JDK.ANNOTATION_UNSIGNED,
        ANNOTATION_SUPER_TYPE_NAME,
        builder -> {
          builder
              .addField("value", Builtin.STRING)
              .addAnnotation(annotationNameType, "jdk.jfr.Unsigned")
              .addAnnotation(annotationContentTypeType, null)
              .addAnnotation(annotationLabelType, "Unsigned value")
              .addAnnotation(
                  annotationDescriptionType, "Value should be interpreted as unsigned data type");
        });

    getOrAdd(
        JDK.TICKSPAN,
        builder -> {
          builder.addField("tickSpan", Builtin.LONG);
        });
    getOrAdd(
        JDK.TICKS,
        builder -> {
          builder.addField("ticks", Builtin.LONG);
        });
    Type threadGroupType =
        getOrAdd(
            JDK.THREAD_GROUP,
            tgBuilder -> {
              tgBuilder
                  .addField("parent", tgBuilder.selfType())
                  .addField("name", getType(Builtin.STRING));
            });
    getOrAdd(
        JDK.THREAD,
        typeBuilder -> {
          typeBuilder
              .addField("osName", getType(Builtin.STRING))
              .addField("osThreadId", getType(Builtin.LONG))
              .addField("javaName", getType(Builtin.STRING))
              .addField("group", threadGroupType);
        });
    Type symbol =
        getOrAdd(
            JDK.SYMBOL,
            builder -> {
              builder.addField("string", Builtin.STRING);
            });
    Type classLoader =
        getOrAdd(
            JDK.CLASS_LOADER,
            builder -> {
              builder.addField("type", JDK.CLASS).addField("name", symbol);
            });
    Type moduleType =
        getOrAdd(
            JDK.MODULE,
            builder -> {
              builder
                  .addField("name", symbol)
                  .addField("version", symbol)
                  .addField("location", symbol)
                  .addField("classLoader", classLoader);
            });
    Type packageType =
        getOrAdd(
            JDK.PACKAGE,
            builder -> {
              builder
                  .addField("name", symbol)
                  .addField("module", moduleType)
                  .addField("exported", Builtin.BOOLEAN);
            });
    Type classType =
        getOrAdd(
            JDK.CLASS,
            builder -> {
              builder
                  .addField("classLoader", classLoader)
                  .addField("name", symbol)
                  .addField("package", packageType)
                  .addField("modifiers", Builtin.INT)
                  .addField("hidden", Builtin.BOOLEAN);
            });
    Type methodType =
        getOrAdd(
            JDK.METHOD,
            builder -> {
              builder
                  .addField("type", classType)
                  .addField("name", symbol)
                  .addField("descriptor", symbol)
                  .addField("modifiers", Builtin.INT)
                  .addField("hidden", Builtin.BOOLEAN);
            });
    getOrAdd(
        JDK.FRAME_TYPE,
        builder -> {
          builder.addField("description", Builtin.STRING);
        });
    getOrAdd(
        JDK.STACK_FRAME,
        builder -> {
          builder
              .addField("method", methodType)
              .addField("lineNumber", Builtin.INT)
              .addField("bytecodeIndex", Builtin.INT)
              .addField("type", JDK.FRAME_TYPE);
        });
    getOrAdd(
        JDK.STACK_TRACE,
        builder -> {
          builder
              .addField("truncated", Builtin.BOOLEAN)
              .addField("frames", JDK.STACK_FRAME, field -> field.asArray());
        });
  }

  /**
   * Retrieve the given type or create it a-new if it hasn't been added yet.
   *
   * @param type the type to retrieve
   * @param builderCallback will be called lazily when the type is about to be initialized
   * @return the corresponding {@link Type type} instance
   */
  public Type getOrAdd(Predefined type, Consumer<CompositeTypeBuilder> builderCallback) {
    return getOrAdd(type.getTypeName(), builderCallback);
  }

  /**
   * Retrieve the given type or create it a-new if it hasn't been added yet.
   *
   * @param name the name of the type to retrieve
   * @param builderCallback will be called lazily when the type is about to be initialized
   * @return the corresponding {@link Type type} instance
   */
  public Type getOrAdd(String name, Consumer<CompositeTypeBuilder> builderCallback) {
    return getOrAdd(name, null, builderCallback);
  }

  /**
   * Retrieve the given type or create it a-new if it hasn't been added yet.
   *
   * @param type the type to retrieve
   * @param supertype the super type name
   * @param builderCallback will be called lazily when the type is about to be initialized
   * @return the corresponding {@link Type type} instance
   */
  public Type getOrAdd(
      Predefined type, String supertype, Consumer<CompositeTypeBuilder> builderCallback) {
    return getOrAdd(type.getTypeName(), supertype, builderCallback);
  }

  /**
   * Retrieve the given type or create it a-new if it hasn't been added yet.
   *
   * @param name the name of the type to retrieve
   * @param supertype the super type name
   * @param builderCallback will be called lazily when the type is about to be initialized
   * @return the corresponding {@link Type type} instance
   */
  public Type getOrAdd(
      String name, String supertype, Consumer<CompositeTypeBuilder> builderCallback) {
    return metadata.registerType(
        name,
        supertype,
        () -> {
          CompositeTypeBuilderImpl builder = new CompositeTypeBuilderImpl(this);
          builderCallback.accept(builder);
          return builder.build();
        });
  }

  /**
   * Retrieve the type by its name.
   *
   * @param name the type name
   * @return the registered {@link Type type} or {@literal null}
   */
  public Type getType(String name) {
    return getType(name, false);
  }

  /**
   * Retrieve the type by its name. If the type hasn't been added yet a {@linkplain ResolvableType}
   * wrapper may be returned.
   *
   * @param name the type name
   * @param asResolvable if {@literal true} a {@linkplain ResolvableType} wrapper will be returned
   *     instead of {@literal null} for non-existent type
   * @return an existing {@link Type} type, {@literal null} or a {@linkplain ResolvableType} wrapper
   */
  public Type getType(String name, boolean asResolvable) {
    return metadata.getType(name, asResolvable);
  }

  /**
   * A convenience shortcut to get a {@linkplain Type} instance corresponding to the {@linkplain
   * Predefined} type
   *
   * @param type the predefined/enum type
   * @return the registered {@linkplain Type} instance or a {@linkplain ResolvableType} wrapper
   */
  public Type getType(Predefined type) {
    return getType(type.getTypeName(), true);
  }

  /**
   * Resolve all unresolved types. After this method had been called all calls to {@linkplain
   * ResolvableType#isResolved()} will return {@literal true} if the target type was properly
   * registered
   */
  public void resolveAll() {
    metadata.resolveTypes();
  }
}
