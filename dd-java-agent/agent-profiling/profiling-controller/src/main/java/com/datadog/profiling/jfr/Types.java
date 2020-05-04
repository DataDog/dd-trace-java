package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** An access class for various {@linkplain JFRType} related operations */
public final class Types {
  interface Predefined extends NamedType {}

  /** Built-in types */
  public enum Builtin implements Predefined {
    BYTE("byte"),
    CHAR("char"),
    SHORT("short"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    STRING("java.lang.String");

    private static Map<String, Builtin> NAME_MAP;
    private final String typeName;

    Builtin(String name) {
      addName(name);
      this.typeName = name;
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

    public static Builtin ofType(JFRType type) {
      return ofName(type.getTypeName());
    }

    @Override
    public String getTypeName() {
      return typeName;
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
    MODULE("jdk.types.Module");

    private static Map<String, JDK> NAME_MAP;
    private final String typeName;

    JDK(String name) {
      addName(name);
      this.typeName = name;
    }

    private static Map<String, JDK> getNameMap() {
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

    public static JDK ofName(String name) {
      return getNameMap().get(name);
    }

    public static JDK ofType(JFRType type) {
      return ofName(type.getTypeName());
    }

    @Override
    public String getTypeName() {
      return typeName;
    }
  }

  private final ConstantPools constantPools = new ConstantPools();
  private final TypeFactory typeFactory = new TypeFactory(constantPools, this);
  private final Metadata metadata = new Metadata(typeFactory);

  Types() {
    registerBuiltins();
    registerJdkTypes();
    metadata.resolveTypes(); // resolve all back-referenced types
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
    JFRType threadGroupType =
        getOrAdd(
            JDK.THREAD_GROUP,
            tgBuilder -> {
              tgBuilder
                  .addField("parent", CustomTypeBuilder.SELF_TYPE)
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
    JFRType symbol =
        getOrAdd(
            JDK.SYMBOL,
            builder -> {
              builder.addField("string", Builtin.STRING);
            });
    JFRType classLoader =
        getOrAdd(
            JDK.CLASS_LOADER,
            builder -> {
              builder.addField("type", JDK.CLASS).addField("name", symbol);
            });
    JFRType moduleType =
        getOrAdd(
            JDK.MODULE,
            builder -> {
              builder
                  .addField("name", symbol)
                  .addField("version", symbol)
                  .addField("location", symbol)
                  .addField("classLoader", classLoader);
            });
    JFRType packageType =
        getOrAdd(
            JDK.PACKAGE,
            builder -> {
              builder
                  .addField("name", symbol)
                  .addField("module", moduleType)
                  .addField("exported", Builtin.BOOLEAN);
            });
    JFRType classType =
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
    JFRType methodType =
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
          builder.addField("truncated", Builtin.BOOLEAN).addArrayField("frames", JDK.STACK_FRAME);
        });
  }

  Metadata getMetadata() {
    return metadata;
  }

  ConstantPools getConstantPools() {
    return constantPools;
  }

  public JFRType getOrAdd(Predefined type, Consumer<CustomTypeBuilder> buildWith) {
    return getOrAdd(type.getTypeName(), buildWith);
  }

  public JFRType getOrAdd(String name, Consumer<CustomTypeBuilder> buildWith) {
    return getOrAdd(name, null, buildWith);
  }

  public JFRType getOrAdd(
      Predefined type, String supertype, Consumer<CustomTypeBuilder> buildWith) {
    return getOrAdd(type.getTypeName(), supertype, buildWith);
  }

  public JFRType getOrAdd(String name, String supertype, Consumer<CustomTypeBuilder> buildWith) {
    return metadata.registerType(
        name,
        supertype,
        () -> {
          CustomTypeBuilder builder = new CustomTypeBuilder(this);
          buildWith.accept(builder);
          return builder.build();
        });
  }

  public JFRType getType(String name) {
    return getType(name, false);
  }

  public JFRType getType(String name, boolean asResolvable) {
    return metadata.getType(name, asResolvable);
  }

  public JFRType getType(Predefined type) {
    return getType(type.getTypeName(), true);
  }

  public void resolveAll() {
    metadata.resolveTypes();
  }
}
