package foo.bar;

public interface TestAbstractStringBuilderSuite<E> {

  E init(final String param);

  E init(final CharSequence param);

  void append(final E target, final String param);

  void append(final E target, final CharSequence param);

  void append(final E target, final CharSequence param, int start, int end);

  void append(final E target, final Object param);

  String substring(final E self, final int beginIndex, final int endIndex);

  String substring(final E self, final int beginIndex);

  CharSequence subSequence(final E self, final int beginIndex, final int endIndex);

  String toString(final E target);
}
