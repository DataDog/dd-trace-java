package datadog.trace.api.cache;

import datadog.trace.api.Functions;
import java.util.function.Function;

public final class QualifiedClassNameCache extends ClassValue<QualifiedClassNameCache.Leaf> {

  private final Function<Class<?>, CharSequence> formatter;
  private final Functions.Join joiner;
  private final int leafSize;

  public QualifiedClassNameCache(
      Function<Class<?>, CharSequence> formatter, Functions.Join joiner) {
    this(formatter, joiner, 16);
  }

  public QualifiedClassNameCache(
      Function<Class<?>, CharSequence> formatter, Functions.Join joiner, int leafSize) {
    this.formatter = formatter;
    this.joiner = joiner;
    this.leafSize = leafSize;
  }

  @Override
  protected Leaf computeValue(Class<?> type) {
    return new Leaf(formatter.apply(type), joiner, leafSize);
  }

  static final class Leaf {

    private final CharSequence name;

    private final DDCache<CharSequence, CharSequence> cache;
    private final Function<CharSequence, CharSequence> joiner;

    private Leaf(CharSequence name, Functions.Join joiner, int leafSize) {
      this.name = name;
      // the class provides a natural bound on the number of elements
      // (e.g. the number of methods)
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
    return get(klass).getName();
  }

  public CharSequence getQualifiedName(Class<?> klass, String qualifier) {
    return get(klass).get(qualifier);
  }
}
