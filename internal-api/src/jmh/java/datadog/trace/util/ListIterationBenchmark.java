package datadog.trace.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.CompilerControl.Mode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark comparing different ways to iterate list of different types and sizes -- both with
 * simple loop bodies (inline case) and complicated loop bodies (dont inline case).
 *
 * <ul>
 *   Compares...
 *   <li>(RECOMMENDED) enhanced for loop / iterator - usually most performant, since escape analysis
 *       usually eliminates iterator allocation
 *   <li>(SITUATIONAL) List.forEach - good when using a non-capturing lambda, when escape analysis
 *       fails to eliminate iterator allocation - good alternative
 *   <li>(SITUATIONAL) c-style i=0; i < list.size() - usually worse than enhanced for - might be
 *       useful with complicated loop body when escape analysis fails to eliminate the iterator
 *   <li>(DISCOURAGED) List.stream - always incurs allocation overhead - usually unnecessary
 *   <li>(DISCOURAGED) List.parallelStream - heavy allocation overhead - only beneficial when
 *       working with sets (uncommon in the java agent)
 *   <li>
 * </ul>
 *
 * <p>Java 17 results (Apple M1, {@code @Fork(2)}, {@code @Threads(8)}; {@code ArrayList}, M ops/s =
 * millions, shown at two sizes): <code>
 * Iteration style       size 10   size 100
 * cstyleFor               1050       165     (fastest)
 * forEach                  995       163
 * enhancedFor              945       153
 * iterator                 935       148     (noisier run-to-run)
 * streams                  158        45     (~3.6x slower; allocates)
 * parallelStreams           ~1      ~0.3     (catastrophic at these sizes)
 * </code>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li>For {@code ArrayList}, the direct styles -- {@code cstyleFor}, {@code forEach},
 *       enhanced-for, and explicit {@code iterator} -- cluster within ~10% of each other; escape
 *       analysis eliminates the iterator allocation, so enhanced-for/iterator stay competitive
 *       while reading cleanest (the RECOMMENDED choice).
 *   <li>{@code stream()} is ~3.6x slower than direct iteration and allocates per call -- avoid on
 *       hot paths.
 *   <li>{@code parallelStream()} is catastrophic for small collections (hundreds of times slower):
 *       ForkJoinPool split/coordinate overhead dwarfs the work, and it is run-to-run erratic. Never
 *       use it for the small lists typical in the agent.
 *   <li>{@code _inline} vs {@code _dont_inline} loop bodies barely differ at these sizes -- the
 *       iteration mechanics dominate, not the body.
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
public class ListIterationBenchmark {
  public static final class Element {
    int num = 0;

    @CompilerControl(Mode.INLINE)
    void manipulate_inline() {
      this.num += 1;
    }

    @CompilerControl(Mode.DONT_INLINE)
    void manipulate_dont_inline() {
      this.num += 1;
    }
  }

  static ArrayList<Element> newArrayList(int size) {
    ArrayList<Element> newList = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      newList.add(new Element());
    }
    return newList;
  }

  /**
   * Describes the list under test as a factory rather than a prebuilt instance. Each benchmark
   * thread builds its own list (with its own {@link Element}s) in {@link #setUp()}, so the {@code
   * manipulate_*} mutations stay thread-local — otherwise, with {@code @Threads(8)} sharing one
   * list held in an enum constant, the benchmark would measure cross-thread contention on {@code
   * Element.num} rather than iteration cost.
   */
  public enum ListSpec {
    COLLECTIONS_EMPTY_LIST(Collections::emptyList),
    EMPTY_ARRAY_LIST(ArrayList::new),
    SINGLETON_LIST(() -> Collections.singletonList(new Element())),
    ARRAY_LIST_1(() -> newArrayList(1)),
    ARRAY_LIST_5(() -> newArrayList(5)),
    ARRAY_LIST_10(() -> newArrayList(10)),
    ARRAY_LIST_100(() -> newArrayList(100));

    private final Supplier<List<Element>> factory;

    ListSpec(Supplier<List<Element>> factory) {
      this.factory = factory;
    }

    List<Element> build() {
      return factory.get();
    }
  }

  @Param ListSpec listSpec;

  List<Element> list;

  @Setup(Level.Trial)
  public void setUp() {
    // Built per thread (the class is @State(Scope.Thread)) so each thread owns its own Elements.
    this.list = this.listSpec.build();
  }

  @Benchmark
  public void forEach_inline() {
    this.list.forEach(Element::manipulate_inline);
  }

  @Benchmark
  public void forEach_dont_inline() {
    this.list.forEach(Element::manipulate_dont_inline);
  }

  @Benchmark
  public void enhancedFor_inline() {
    // Enhanced for-loop is just syntax sugar for an Iterator
    for (Element e : this.list) {
      e.manipulate_inline();
    }
  }

  @Benchmark
  public void enhancedFor_dont_inline() {
    // Enhanced for-loop is just syntax sugar for an Iterator
    for (Element e : this.list) {
      e.manipulate_dont_inline();
    }
  }

  @Benchmark
  public void iterator_inline() {
    for (Iterator<Element> iter = this.list.iterator(); iter.hasNext(); ) {
      iter.next().manipulate_inline();
    }
  }

  @Benchmark
  public void iterator_dont_inline() {
    for (Iterator<Element> iter = this.list.iterator(); iter.hasNext(); ) {
      iter.next().manipulate_dont_inline();
    }
  }

  @Benchmark
  public void cstyleFor_inline() {
    for (int i = 0; i < this.list.size(); ++i) {
      this.list.get(i).manipulate_inline();
    }
  }

  @Benchmark
  public void cstyleFor_dont_inline() {
    for (int i = 0; i < this.list.size(); ++i) {
      this.list.get(i).manipulate_dont_inline();
    }
  }

  @Benchmark
  public void streams_inline() {
    this.list.stream().forEach(Element::manipulate_inline);
  }

  @Benchmark
  public void streams_dont_inline() {
    this.list.stream().forEach(Element::manipulate_dont_inline);
  }

  @Benchmark
  public void parallelStreams_inline() {
    this.list.parallelStream().forEach(Element::manipulate_inline);
  }

  @Benchmark
  public void parallelStreams_dont_inline() {
    this.list.parallelStream().forEach(Element::manipulate_dont_inline);
  }
}
