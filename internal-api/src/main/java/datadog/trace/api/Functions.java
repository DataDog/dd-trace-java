package datadog.trace.api;

import static java.util.function.Function.identity;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Functions {

  private Functions() {}

  public abstract static class Concatenate {

    public CharSequence concatenate(CharSequence left, CharSequence right) {
      return UTF8BytesString.create(String.valueOf(left) + right);
    }
  }

  public static final class Suffix extends Concatenate
      implements Function<CharSequence, CharSequence> {
    private final CharSequence suffix;
    private final Function<CharSequence, CharSequence> transformer;

    public Suffix(CharSequence suffix, Function<CharSequence, CharSequence> transformer) {
      this.suffix = suffix;
      this.transformer = transformer;
    }

    public Suffix(String suffix) {
      this(suffix, identity());
    }

    @Override
    public CharSequence apply(CharSequence key) {
      return concatenate(transformer.apply(key), suffix);
    }
  }

  public static final class Prefix extends Concatenate
      implements Function<CharSequence, CharSequence> {
    private final CharSequence prefix;
    private final Function<CharSequence, CharSequence> transformer;

    public Prefix(CharSequence prefix, Function<CharSequence, CharSequence> transformer) {
      this.prefix = prefix;
      this.transformer = transformer;
    }

    public Prefix(CharSequence prefix) {
      this(prefix, identity());
    }

    @Override
    public CharSequence apply(CharSequence key) {
      return concatenate(prefix, transformer.apply(key));
    }
  }

  public abstract static class Join
      implements BiFunction<CharSequence, CharSequence, CharSequence> {
    protected final CharSequence joiner;
    protected final Function<CharSequence, CharSequence> transformer;

    protected Join(CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      this.joiner = joiner;
      this.transformer = transformer;
    }

    @Override
    public CharSequence apply(CharSequence left, CharSequence right) {
      return UTF8BytesString.create(String.valueOf(left) + joiner + right);
    }

    public abstract Function<CharSequence, CharSequence> curry(CharSequence specialisation);
  }

  public static class PrefixJoin extends Join {

    public PrefixJoin(CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      super(joiner, transformer);
    }

    @Override
    public Function<CharSequence, CharSequence> curry(CharSequence specialisation) {
      return new Prefix(String.valueOf(specialisation) + joiner, transformer);
    }

    public static PrefixJoin of(
        CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      return new PrefixJoin(joiner, transformer);
    }

    public static PrefixJoin of(String joiner) {
      return of(joiner, identity());
    }

    public static final PrefixJoin ZERO = of("");
  }

  public static class SuffixJoin extends Join {

    public SuffixJoin(CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      super(joiner, transformer);
    }

    @Override
    public Function<CharSequence, CharSequence> curry(CharSequence specialisation) {
      return new Suffix(String.valueOf(joiner) + specialisation, transformer);
    }

    public static SuffixJoin of(
        CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      return new SuffixJoin(joiner, transformer);
    }

    public static SuffixJoin of(CharSequence joiner) {
      return of(joiner, identity());
    }

    public static final SuffixJoin ZERO = of("");
  }

  public static final Function<String, UTF8BytesString> UTF8_ENCODE = UTF8BytesString::create;

  public static final class LowerCase implements Function<String, String> {

    public static final LowerCase INSTANCE = new LowerCase();

    @Override
    public String apply(String key) {
      return key.toLowerCase(Locale.ROOT);
    }
  }

  public static final class ToString<T> implements Function<T, String> {

    @Override
    public String apply(T key) {
      return key.toString();
    }
  }

  public static <T> Function<?, T> newInstanceOf(Class<T> clazz) {
    return new NewInstance<>(clazz);
  }

  @SuppressWarnings("unchecked")
  private static final class NewInstance<Object, T> implements Function<Object, T> {

    private final MethodHandle methodHandle;

    private NewInstance(Class<T> type) {
      try {
        this.methodHandle =
            MethodHandles.lookup().findConstructor(type, MethodType.methodType(void.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public T apply(Object input) {
      try {
        // can't invokeExact because the return type is Object
        // in this context
        return (T) methodHandle.invoke();
      } catch (Throwable throwable) {
        return null;
      }
    }
  }
}
