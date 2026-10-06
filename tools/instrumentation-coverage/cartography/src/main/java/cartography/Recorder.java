package cartography;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.gson.GsonBuilder;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** Independent test-window recorder. Never reads, activates, or transports tracer Context. */
public final class Recorder {
  public static final List<String> errors = Collections.synchronizedList(new ArrayList<>());
  public static final Set<String> transformed = Collections.synchronizedSet(new TreeSet<>());
  public static final List<Window> windows = new ArrayList<>();
  public static volatile Window active;
  public static boolean installed;
  public static int repetition;
  private static final ThreadLocal<Deque<Token>> stack = ThreadLocal.withInitial(ArrayDeque::new);

  public static void premain(String args, Instrumentation inst) throws Exception {
    Set<String> classes =
        new HashSet<>(Files.readAllLines(Path.of(System.getProperty("cartography.classes"))));
    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("cartography.")))
        .type(namedOneOf(classes.toArray(new String[0])))
        .transform(
            (builder, type, loader, module, domain) ->
                builder.visit(
                    Advice.to(Entry.class)
                        .on(
                            isMethod()
                                .and(not(isAbstract()))
                                .and(not(isNative()))
                                .and(not(isSynthetic()))
                                .and(not(isBridge())))))
        .with(
            new AgentBuilder.Listener.Adapter() {
              public void onTransformation(
                  TypeDescription type,
                  ClassLoader loader,
                  JavaModule module,
                  boolean loaded,
                  DynamicType dynamicType) {
                transformed.add(type.getName());
              }

              public void onError(
                  String type,
                  ClassLoader loader,
                  JavaModule module,
                  boolean loaded,
                  Throwable error) {
                errors.add(type + ": " + error);
              }
            })
        .installOn(inst);
    installed = true;
  }

  public static synchronized void begin(String id, String display) {
    if (active != null) throw new IllegalStateException("Overlapping test windows");
    active = new Window(id, display, repetition, Thread.currentThread().getId());
  }

  public static synchronized void end() {
    Window w = active;
    active = null;
    if (w != null) {
      synchronized (w) {
        w.finished = true;
      }
      windows.add(w);
    }
  }

  public static Token enter(String method) {
    Window w = active;
    if (w == null) return null;
    Deque<Token> frames = stack.get();
    Token parent = frames.peek();
    Token t = new Token(w, method);
    frames.push(t);
    synchronized (w) {
      if (w.finished) return t;
      w.methods.merge(method, 1L, Long::sum);
      if (w.methods.size() > 20000) throw new IllegalStateException("Method budget exceeded");
      String edge =
          (parent != null && parent.window == w ? parent.method : "<thread-entry>")
              + " -> "
              + method;
      w.edges.merge(edge, 1L, Long::sum);
      if (w.edges.size() > 100000) throw new IllegalStateException("Edge budget exceeded");
      if (Thread.currentThread().getId() != w.initiatingThread) w.temporalWorkerEntries++;
    }
    return t;
  }

  public static void exit(Token t) {
    if (t == null) return;
    Deque<Token> frames = stack.get();
    if (frames.peek() != t) throw new IllegalStateException("Unbalanced recording stack");
    frames.pop();
  }

  public static void save(Path path, Map<String, Object> outcomes) throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", 1);
    data.put("installed", installed);
    data.put(
        "semantics",
        "Observed method counts and nesting between selected methods. Test-body windows exclude fixtures; worker attribution is temporal. No causal async edges or Context signals.");
    data.put("javaVersion", System.getProperty("java.version"));
    data.put(
        "runtimeManifest",
        Files.readString(Path.of(System.getProperty("cartography.runtime-manifest"))));
    data.put("errors", errors);
    data.put("transformedClasses", transformed);
    data.put("windows", windows);
    data.put("outcomes", outcomes);
    Files.createDirectories(path.getParent());
    Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(data));
  }

  public static final class Window {
    public final String id, display;
    public final int repetition;
    public final long initiatingThread;
    public final Map<String, Long> methods = new TreeMap<>(), edges = new TreeMap<>();
    public long temporalWorkerEntries;
    public boolean finished;

    Window(String id, String display, int repetition, long thread) {
      this.id = id;
      this.display = display;
      this.repetition = repetition;
      initiatingThread = thread;
    }
  }

  public static final class Token {
    final Window window;
    final String method;

    Token(Window w, String m) {
      window = w;
      method = m;
    }
  }

  public static final class Entry {
    @Advice.OnMethodEnter
    public static Token enter(@Advice.Origin("#t.#m#d") String method) {
      return Recorder.enter(method);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter Token token) {
      Recorder.exit(token);
    }
  }
}
