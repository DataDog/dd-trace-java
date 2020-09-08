package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public final class Functions {

  private Functions() {}

  // the majority of this can be removed/simplified in JDK8

  public static final class Zero<T> implements Function<T, T> {

    @Override
    public T apply(T input) {
      return input;
    }
  }

  @SuppressWarnings("rawtypes")
  private static final Zero ZERO = new Zero();

  @SuppressWarnings("unchecked")
  public static <T> Zero<T> zero() {
    return (Zero<T>) ZERO;
  }

  public abstract static class Concatenate
      implements TwoArgFunction<CharSequence, CharSequence, CharSequence> {

    @Override
    public CharSequence apply(CharSequence left, CharSequence right) {
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
      this(suffix, Functions.<CharSequence>zero());
    }

    @Override
    public CharSequence apply(CharSequence key) {
      return apply(transformer.apply(key), suffix);
    }

    @Override
    public Function<CharSequence, CharSequence> curry(CharSequence suffix) {
      return new Suffix(suffix, transformer);
    }

    public static final Suffix ZERO = new Suffix("", Functions.<CharSequence>zero());
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
      this(prefix, Functions.<CharSequence>zero());
    }

    @Override
    public CharSequence apply(CharSequence key) {
      return apply(prefix, transformer.apply(key));
    }

    @Override
    public Function<CharSequence, CharSequence> curry(CharSequence prefix) {
      return new Prefix(prefix, transformer);
    }

    public static final Prefix ZERO = new Prefix("", Functions.<CharSequence>zero());
  }

  public abstract static class Join
      implements TwoArgFunction<CharSequence, CharSequence, CharSequence> {
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
      return of(joiner, Functions.<CharSequence>zero());
    }
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
      return of(joiner, Functions.<CharSequence>zero());
    }
  }

  public static final class LowerCase implements Function<String, String> {

    public static final LowerCase INSTANCE = new LowerCase();

    @Override
    public String apply(String key) {
      return key.toLowerCase();
    }
  }

  public static final class ToString<T> implements Function<T, String> {

    @Override
    public String apply(T key) {
      return key.toString();
    }
  }
}
