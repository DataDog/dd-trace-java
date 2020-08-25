package datadog.trace.bootstrap.instrumentation.cache;

import datadog.trace.bootstrap.instrumentation.api.Function;
import datadog.trace.bootstrap.instrumentation.api.TwoArgFunction;

public final class QualifiedClassNameCache {

  private final Root root;

  public QualifiedClassNameCache(
      Function<Class<?>, CharSequence> formatter,
      TwoArgFunction<CharSequence, CharSequence, CharSequence> joiner) {
    this(formatter, joiner, 16);
  }

  public QualifiedClassNameCache(
      Function<Class<?>, CharSequence> formatter,
      TwoArgFunction<CharSequence, CharSequence, CharSequence> joiner,
      int leafSize) {
    this.root = new Root(formatter, joiner, leafSize);
  }

  private static final class Root extends ClassValue<Leaf> {

    private final Function<Class<?>, CharSequence> formatter;
    private final TwoArgFunction<CharSequence, CharSequence, CharSequence> joiner;
    private final int leafSize;

    private Root(
        Function<Class<?>, CharSequence> formatter,
        TwoArgFunction<CharSequence, CharSequence, CharSequence> joiner,
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

    private final CharSequence name;

    private final DDCache<CharSequence, CharSequence> cache;
    private final Function<CharSequence, CharSequence> joiner;

    private Leaf(
        CharSequence name,
        TwoArgFunction<CharSequence, CharSequence, CharSequence> joiner,
        int leafSize) {
      this.name = name;
      this.cache = DDCaches.newUnboundedCache(leafSize);
      this.joiner = joiner.curry(name);
    }

    CharSequence get(CharSequence name) {
      return cache.computeIfAbsent(name, joiner);
    }

    CharSequence getName() {
      return name;
    }
  }

  public CharSequence getClassName(Class<?> klass) {
    return root.get(klass).getName();
  }

  public CharSequence getQualifiedName(Class<?> klass, String qualifier) {
    return root.get(klass).get(qualifier);
  }
}
