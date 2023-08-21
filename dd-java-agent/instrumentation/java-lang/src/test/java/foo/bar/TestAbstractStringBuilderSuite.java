package foo.bar;

public interface TestAbstractStringBuilderSuite<E> {

  E init(final String param);

  E init(final CharSequence param);

  void append(final E target, final String param);

  void append(final E target, final CharSequence param);

  String toString(final E target);
}
