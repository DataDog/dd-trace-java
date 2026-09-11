package datadog.trace.api;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Models projecting a typed source's fields onto a span three ways — the decorator template-method
 * pattern vs. the {@code TagExtractor} / {@code TagContributor} shape — to show what compiles away
 * and what doesn't. Uses synthetic structural equivalents (a light DCE-safe {@link Sink} stands in
 * for the span) so it isolates *dispatch* cost, not span/tag-storage cost; the real interfaces
 * target {@code AgentSpan}, but the call-site shape is what this measures.
 *
 * <p>{@code mode}:
 *
 * <ul>
 *   <li><b>mono</b> — one concrete type at the call site. The JIT devirtualizes + inlines all three
 *       → they should ~tie (the abstraction is free at the decorator's BEST case — charitable,
 *       since a real program loads many decorators).
 *   <li><b>mega</b> — {@link #TYPES} equivalent loaded types, exercised so the call site (and, for
 *       the decorator, the shared base's template-method calls) go megamorphic. The decorator is
 *       FORCED here in reality (its template dispatch is structural). Extractor/contributor are
 *       shown in mega too to prove they are not magic: routed through a shared megamorphic site
 *       they degrade identically. The real win is that they CAN stay mono (per-integration site)
 *       and the decorator cannot.
 * </ul>
 *
 * <p>Run with {@code -prof gc} (captures should stay flat) and {@code -XX:+PrintInlining} to
 * confirm the mechanism: decorator template calls megamorphic/not-inlined under mega;
 * extractor/contributor inlined under mono. Throughput is the headline; per your methodology,
 * multi-thread reveals alloc pressure and 3+ forks expose bimodal JIT/devirt behavior.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class TagProjectionBenchmark {
  static final int TYPES = 8;

  @Param({"mono", "mega"})
  String mode;

  /** Light DCE-safe stand-in for the span — identical trivial work per set across all arms. */
  static final class Sink {
    long acc;

    void set(String key, Object value) {
      this.acc += key.length() + (value == null ? 0 : value.hashCode());
    }
  }

  /** The typed source whose fields are projected (a stand-in for DbInfo). */
  static final class DbPojo {
    final String dbType, dbUser, dbInstance, dbHostname;

    DbPojo(String t, String u, String i, String h) {
      this.dbType = t;
      this.dbUser = u;
      this.dbInstance = i;
      this.dbHostname = h;
    }
  }

  // ---- (1) decorator template-method pattern: shared base onConnection() calls abstract hooks
  // ----
  abstract static class TemplateDecorator {
    abstract String dbType(DbPojo p);

    abstract String dbUser(DbPojo p);

    abstract String dbInstance(DbPojo p);

    abstract String dbHostname(DbPojo p);

    // shared base: calls the abstract template methods -> megamorphic when many subclasses loaded
    final void onConnection(Sink sink, DbPojo p) {
      String type = dbType(p);
      if (type != null) sink.set("db.type", type);
      String user = dbUser(p);
      if (user != null) sink.set("db.user", user);
      String instance = dbInstance(p);
      if (instance != null) sink.set("db.instance", instance);
      String host = dbHostname(p);
      if (host != null) sink.set("peer.hostname", host);
    }
  }

  // ---- (2) extractor shape: stateless lambda-style, reads source -> sets directly ----
  interface Extractor {
    void extract(DbPojo p, Sink sink);
  }

  // ---- (3) contributor shape: the source projects itself ----
  interface Contributor {
    void addTo(Sink sink);
  }

  private DbPojo[] pojos;
  private TemplateDecorator[] decorators;
  private Extractor[] extractors;
  private Contributor[] contributors;
  private Sink sink;
  private int idx;

  @Setup(Level.Trial)
  public void setup() {
    this.sink = new Sink();
    this.pojos = new DbPojo[TYPES];
    this.decorators = new TemplateDecorator[TYPES];
    this.extractors = new Extractor[TYPES];
    this.contributors = new Contributor[TYPES];
    for (int i = 0; i < TYPES; i++) {
      DbPojo pojo = new DbPojo("h2", "sa", "db" + i, "localhost");
      this.pojos[i] = pojo;
      this.decorators[i] = newDecorator(i);
      this.extractors[i] = newExtractor(i);
      this.contributors[i] = newContributor(pojo, i);
    }
  }

  // distinct concrete types per index so 'mega' has TYPES loaded implementations to megamorphize
  private static TemplateDecorator newDecorator(int i) {
    switch (i) {
      case 0:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      case 1:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      case 2:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      case 3:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      case 4:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      case 5:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      case 6:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
      default:
        return new TemplateDecorator() {
          String dbType(DbPojo p) {
            return p.dbType;
          }

          String dbUser(DbPojo p) {
            return p.dbUser;
          }

          String dbInstance(DbPojo p) {
            return p.dbInstance;
          }

          String dbHostname(DbPojo p) {
            return p.dbHostname;
          }
        };
    }
  }

  // distinct Extractor lambda expressions -> distinct synthetic types (megamorphic when rotated)
  private static Extractor newExtractor(int i) {
    switch (i) {
      case 0:
        return TagProjectionBenchmark::extract;
      case 1:
        return (p, s) -> extract(p, s);
      case 2:
        return (DbPojo p, Sink s) -> extract(p, s);
      case 3:
        return (p, s) -> {
          extract(p, s);
        };
      case 4:
        return (final DbPojo p, final Sink s) -> extract(p, s);
      case 5:
        return (p, s) -> extract(p, s);
      case 6:
        return (DbPojo p, Sink s) -> {
          extract(p, s);
        };
      default:
        return (p, s) -> extract(p, s);
    }
  }

  // the actual projection, shared so all extractor variants do identical work
  private static void extract(DbPojo p, Sink sink) {
    if (p.dbType != null) sink.set("db.type", p.dbType);
    if (p.dbUser != null) sink.set("db.user", p.dbUser);
    if (p.dbInstance != null) sink.set("db.instance", p.dbInstance);
    if (p.dbHostname != null) sink.set("peer.hostname", p.dbHostname);
  }

  private static Contributor newContributor(DbPojo p, int i) {
    switch (i) {
      case 0:
        return s -> extract(p, s);
      case 1:
        return (Sink s) -> extract(p, s);
      case 2:
        return s -> {
          extract(p, s);
        };
      case 3:
        return (final Sink s) -> extract(p, s);
      case 4:
        return s -> extract(p, s);
      case 5:
        return (Sink s) -> {
          extract(p, s);
        };
      case 6:
        return s -> extract(p, s);
      default:
        return (Sink s) -> extract(p, s);
    }
  }

  // mono: always index 0 (one type at the site). mega: rotate -> the site sees all TYPES.
  private int next() {
    if ("mono".equals(this.mode)) return 0;
    int i = this.idx + 1;
    if (i >= TYPES) i = 0;
    this.idx = i;
    return i;
  }

  @Benchmark
  public long decorator() {
    int i = next();
    this.decorators[i].onConnection(this.sink, this.pojos[i]);
    return this.sink.acc;
  }

  @Benchmark
  public long extractor() {
    int i = next();
    this.extractors[i].extract(this.pojos[i], this.sink);
    return this.sink.acc;
  }

  @Benchmark
  public long contributor() {
    int i = next();
    this.contributors[i].addTo(this.sink);
    return this.sink.acc;
  }
}
