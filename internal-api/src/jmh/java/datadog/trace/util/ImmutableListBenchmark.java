package datadog.trace.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Read-side benchmark for small immutable lists held in long-lived parsed state, such as a
 * configuration whose lists mostly hold zero or one element: how each representation iterates, and
 * whether the iterator allocates.
 *
 * <p>Each structure holds the same five lists, of sizes 0, 1, 1, 2 and 8, and each operation walks
 * all five, so the benchmark's own call sites see whatever mix of list classes the structure
 * produces:
 *
 * <ul>
 *   <li>{@code arrayList}: exact-size {@code ArrayList}s; one class.
 *   <li>{@code jdkImmutable}: {@link CollectionUtils#tryMakeImmutableList} ({@code List.of}'s
 *       {@code List12} and {@code ListN} on Java 10+).
 *   <li>{@code jdkMixed}: {@code emptyList}, {@code singletonList} or an {@code ArrayList} by size,
 *       as parsers commonly build them; three classes.
 *   <li>{@code compactInherited}: a compact list (one object, plus an exact-size array beyond one
 *       element) that inherits {@code AbstractList}'s iterator.
 *   <li>{@code compact}: the toolbox {@link CompactList}, with its own iterator, {@code forEach}
 *       and {@code isEmpty}, returning a new iterator on every path.
 * </ul>
 *
 * <p>Operations: an enhanced-for loop, {@code forEach} with a lambda, an indexed loop, and for
 * {@code compact} also its {@link CompactList#cursor() cursor}.
 *
 * <p>{@code profile}: {@code clean} leaves the JDK's shared list internals as a quiet benchmark JVM
 * has them; {@code polluted} first runs {@link BenchmarkUtils#warmUpListDispatch}, so {@code
 * AbstractList}'s iterator, {@code AbstractCollection.isEmpty()} and {@code Iterable.forEach}'s
 * default loop see many list classes, as in an application. Iterators that escape analysis removes
 * in a clean profile can come back in a polluted one; {@code -prof gc} shows it as bytes per
 * operation.
 */
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(4)
@State(Scope.Benchmark)
public class ImmutableListBenchmark {
  static final int[] SIZES = {0, 1, 1, 2, 8};

  @Param({"clean", "polluted"})
  public String profile;

  public static final class Element {
    final int value;

    Element(final int value) {
      this.value = value;
    }
  }

  // Built once, never mutated: safe to share across the reader threads.
  List<Element>[] arrayList;
  List<Element>[] jdkImmutable;
  List<Element>[] jdkMixed;
  List<Element>[] compactInherited;
  CompactList<Element>[] compact;

  @Setup(Level.Trial)
  public void setUp(final Blackhole bh) {
    if ("polluted".equals(profile)) {
      BenchmarkUtils.warmUpListDispatch(bh);
    }
    arrayList = lists();
    jdkImmutable = lists();
    jdkMixed = lists();
    compactInherited = lists();
    compact = compactLists();
    for (int k = 0; k < SIZES.length; k++) {
      final List<Element> source = new ArrayList<>();
      for (int i = 0; i < SIZES[k]; i++) {
        source.add(new Element(k * 10 + i));
      }
      arrayList[k] = new ArrayList<>(source);
      jdkImmutable[k] = CollectionUtils.tryMakeImmutableList(source);
      jdkMixed[k] =
          source.isEmpty()
              ? Collections.<Element>emptyList()
              : source.size() == 1
                  ? Collections.singletonList(source.get(0))
                  : new ArrayList<>(source);
      compactInherited[k] = InheritedCompactList.copyOf(source);
      compact[k] = CompactList.copyOf(source);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Element>[] lists() {
    return (List<Element>[]) new List<?>[SIZES.length];
  }

  @SuppressWarnings("unchecked")
  private static CompactList<Element>[] compactLists() {
    return (CompactList<Element>[]) new CompactList<?>[SIZES.length];
  }

  // ---- Enhanced for --------------------------------------------------------------------------

  static int enhancedFor(final List<Element>[] lists) {
    int sum = 0;
    for (final List<Element> list : lists) {
      for (final Element element : list) {
        sum += element.value;
      }
    }
    return sum;
  }

  @Benchmark
  public int enhancedFor_arrayList() {
    return enhancedFor(arrayList);
  }

  @Benchmark
  public int enhancedFor_jdkImmutable() {
    return enhancedFor(jdkImmutable);
  }

  @Benchmark
  public int enhancedFor_jdkMixed() {
    return enhancedFor(jdkMixed);
  }

  @Benchmark
  public int enhancedFor_compactInherited() {
    return enhancedFor(compactInherited);
  }

  @Benchmark
  public int enhancedFor_compact() {
    return enhancedFor(compact);
  }

  // ---- forEach -------------------------------------------------------------------------------

  static void forEach(final List<Element>[] lists, final Blackhole bh) {
    for (final List<Element> list : lists) {
      list.forEach(element -> bh.consume(element.value));
    }
  }

  @Benchmark
  public void forEach_arrayList(final Blackhole bh) {
    forEach(arrayList, bh);
  }

  @Benchmark
  public void forEach_jdkImmutable(final Blackhole bh) {
    forEach(jdkImmutable, bh);
  }

  @Benchmark
  public void forEach_jdkMixed(final Blackhole bh) {
    forEach(jdkMixed, bh);
  }

  @Benchmark
  public void forEach_compactInherited(final Blackhole bh) {
    forEach(compactInherited, bh);
  }

  @Benchmark
  public void forEach_compact(final Blackhole bh) {
    forEach(compact, bh);
  }

  // ---- Indexed -------------------------------------------------------------------------------

  static int indexed(final List<Element>[] lists) {
    int sum = 0;
    for (final List<Element> list : lists) {
      for (int i = 0; i < list.size(); i++) {
        sum += list.get(i).value;
      }
    }
    return sum;
  }

  @Benchmark
  public int indexed_arrayList() {
    return indexed(arrayList);
  }

  @Benchmark
  public int indexed_jdkImmutable() {
    return indexed(jdkImmutable);
  }

  @Benchmark
  public int indexed_jdkMixed() {
    return indexed(jdkMixed);
  }

  @Benchmark
  public int indexed_compactInherited() {
    return indexed(compactInherited);
  }

  @Benchmark
  public int indexed_compact() {
    return indexed(compact);
  }

  // ---- Cursor --------------------------------------------------------------------------------

  @Benchmark
  public int cursor_compact() {
    int sum = 0;
    for (final CompactList<Element> list : compact) {
      final Cursor<Element> cursor = list.cursor();
      for (Element element = cursor.next(); element != null; element = cursor.next()) {
        sum += element.value;
      }
    }
    return sum;
  }

  // ---- The baseline compact list -------------------------------------------------------------

  /** A compact list that inherits {@code AbstractList}'s iterator, {@code isEmpty} and forEach. */
  static final class InheritedCompactList<E> extends AbstractList<E> implements RandomAccess {
    private static final Object[] NO_MORE = new Object[0];
    private static final InheritedCompactList<Object> EMPTY =
        new InheritedCompactList<>(null, null);

    private final E first;
    private final Object[] more; // null for the empty list, NO_MORE for one element

    private InheritedCompactList(final E first, final Object[] more) {
      this.first = first;
      this.more = more;
    }

    @SuppressWarnings("unchecked")
    static <E> List<E> copyOf(final List<? extends E> list) {
      switch (list.size()) {
        case 0:
          return (List<E>) EMPTY;
        case 1:
          return new InheritedCompactList<>(list.get(0), NO_MORE);
        default:
          return new InheritedCompactList<>(list.get(0), list.subList(1, list.size()).toArray());
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(final int index) {
      final Object[] rest = more;
      final int size = rest == null ? 0 : rest.length + 1;
      if (index < 0 || index >= size) {
        throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
      }
      return index == 0 ? first : (E) rest[index - 1];
    }

    @Override
    public int size() {
      return more == null ? 0 : more.length + 1;
    }
  }
}
