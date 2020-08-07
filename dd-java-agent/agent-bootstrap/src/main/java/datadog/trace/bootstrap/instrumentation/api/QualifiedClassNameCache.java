package datadog.trace.bootstrap.instrumentation.api;

public class QualifiedClassNameCache {

  private final Root root;

  public QualifiedClassNameCache(
      Function<Class<?>, String> formatter, TwoArgFunction<String, String, String> joiner) {
    this(formatter, joiner, 16);
  }

  public QualifiedClassNameCache(
      Function<Class<?>, String> formatter,
      TwoArgFunction<String, String, String> joiner,
      int leafSize) {
    this.root = new Root(formatter, joiner, leafSize);
  }

  private static final class Root extends ClassValue<Leaf> {

    private final Function<Class<?>, String> formatter;
    private final TwoArgFunction<String, String, String> joiner;
    private final int leafSize;

    private Root(
        Function<Class<?>, String> formatter,
        TwoArgFunction<String, String, String> joiner,
        int leafSize) {
      this.formatter = formatter;
      this.joiner = joiner;
      this.leafSize = leafSize;
    }

    @Override
    protected Leaf computeValue(Class<?> type) {
      return new Leaf(formatter.apply(type), joiner, leafSize);
    }
  }

  private static class Leaf {

    private final String name;

    private final FixedSizeCache<String, String> cache;
    private final Function<String, String> joiner;

    private Leaf(String name, TwoArgFunction<String, String, String> joiner, int leafSize) {
      ;
      this.name = name;
      this.cache = new FixedSizeCache<>(leafSize);
      this.joiner = joiner.curry(name);
    }

    String get(String name) {
      return cache.computeIfAbsent(name, joiner);
    }

    String getName() {
      return name;
    }
  }

  public String getClassName(Class<?> klass) {
    return root.get(klass).getName();
  }

  public String getQualifiedName(Class<?> klass, String qualifier) {
    return root.get(klass).get(qualifier);
  }
}
