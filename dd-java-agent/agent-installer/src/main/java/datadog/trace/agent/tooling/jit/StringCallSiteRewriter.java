package datadog.trace.agent.tooling.jit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PROTOTYPE: rewrites calls to selected JDK methods (and a few short call sequences) into calls to
 * helpers in {@code datadog.trace.bootstrap.jit}, at class-load time.
 *
 * <p>Three kinds of rewrite, all in place (no instruction moves to a different offset, so branch
 * offsets, exception tables, stack-map frames and debug tables stay valid):
 *
 * <ul>
 *   <li><b>Single call:</b> one {@code invoke*} (3 bytes) becomes an {@code invokestatic} of a
 *       helper that takes the same operands (receiver first).
 *   <li><b>Slotted call:</b> the same, but the helper is a per-site trampoline in a chunk class
 *       ({@link JitSiteChunks}), so the helper can keep per-site state (counters, tiers).
 *   <li><b>Window:</b> a short straight-line sequence (e.g. {@code Pattern.compile(r)}, load {@code
 *       s}, {@code .matcher(s)}, {@code .matches()}) becomes an optional single local/constant load
 *       followed by one {@code invokestatic}, padded with {@code nop}s to the original length. A
 *       window is only rewritten if nothing can observe the offsets inside it: no branch target,
 *       exception range boundary or handler, stack-map frame, line number or local variable range
 *       starts strictly inside it, and the method has no type annotations on code. Intermediate
 *       values (the Pattern, the Matcher, the formatter) never escape the window. When a load is
 *       moved ahead of a first call that takes two or more operands, the stack holds one more value
 *       than the original did, so the method's {@code max_stack} (a fixed-size field) is raised by
 *       one.
 * </ul>
 *
 * <p>The helpers' constant-pool entries are appended to the pool. Nothing is parsed beyond the
 * constant pool unless it mentions one of the target names. Any unexpected input leaves the class
 * untouched.
 */
public final class StringCallSiteRewriter implements ClassFileTransformer {
  private static final String JIT = "datadog/trace/bootstrap/jit/";
  private static final String STRINGS = JIT + "StringRewrites";
  private static final String FUSED = JIT + "FusedRewrites";
  private static final String LOOKUPS = JIT + "LookupRewrites";
  private static final String FUSIONS = JIT + "StringFusions";
  private static final String JDK = JIT + "JdkRewrites";
  private static final String NUMBERS = JIT + "NumberFusions";
  private static final String COLLECTIONS = JIT + "CollectionFusions";
  private static final String FORMATS = JIT + "FormatRewrites";

  private static final int NOP = 0x00;
  private static final int LDC = 0x12;
  private static final int LDC_W = 0x13;
  private static final int ALOAD = 0x19;
  private static final int DUP = 0x59;
  private static final int INVOKEVIRTUAL = 0xB6;
  private static final int INVOKESPECIAL = 0xB7;
  private static final int INVOKESTATIC = 0xB8;
  private static final int NEW = 0xBB;
  private static final int INVOKEINTERFACE = 0xB9;
  private static final int ANEWARRAY = 0xBD;
  private static final int ICONST_0 = 0x03;

  static final int SINGLE = 0;
  static final int SLOTTED = 1;
  static final int PART = 2;

  // roles of window parts
  static final int COMPILE = 1;
  static final int MATCHER = 2;
  static final int MATCHER_MATCHES = 3;
  static final int MATCHER_FIND = 4;
  static final int MATCHER_LOOKING_AT = 5;
  static final int PATTERN_SPLIT = 6;
  static final int OF_PATTERN = 7;
  static final int TEMPORAL_FORMAT = 8;
  static final int FORMATTER_FORMAT = 9;
  static final int CONSTRUCTOR = 10;
  static final int CASE_LOWER = 11;
  static final int CASE_LOWER_LOCALE = 12;
  static final int CASE_UPPER = 13;
  static final int CASE_UPPER_LOCALE = 14;
  static final int SUBSTRING_FROM = 15;
  static final int SUBSTRING_RANGE = 16;
  static final int TRIM = 17;
  static final int STRING_EQUALS = 18;
  static final int STRING_STARTS_WITH = 19;
  static final int STRING_ENDS_WITH = 20;
  static final int STRING_IS_EMPTY = 21;
  static final int STRING_LENGTH = 22;
  static final int PARSE_INT = 23;
  static final int PARSE_LONG = 24;
  static final int VALUE_OF_INT = 25;
  static final int COLLECTION_SIZE = 26;
  static final int COLLECTION_TO_ARRAY = 27;

  /**
   * Roles that start a window (the rest only end one, so they don't make a method worth scanning).
   */
  static boolean startsWindow(int role) {
    return role == COMPILE
        || role == OF_PATTERN
        || role == CONSTRUCTOR
        || (role >= CASE_LOWER && role <= TRIM)
        || role == COLLECTION_SIZE;
  }

  /** A call target: a Methodref and what to do with calls to it. */
  static final class Target {
    final int opcode;
    final String owner;
    final String name;
    final String descriptor;
    final int kind;
    final int role;

    /** SINGLE: the helper; SLOTTED: the unslotted helper used when no slot is available. */
    final Helper helper;

    /** SLOTTED: trampoline name prefix and descriptor. */
    final String slotPrefix;

    final String slotDescriptor;

    private Target(
        int opcode,
        String owner,
        String name,
        String descriptor,
        int kind,
        int role,
        Helper helper,
        String slotPrefix,
        String slotDescriptor) {
      this.opcode = opcode;
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
      this.kind = kind;
      this.role = role;
      this.helper = helper;
      this.slotPrefix = slotPrefix;
      this.slotDescriptor = slotDescriptor;
    }

    /** The call's operands as a static descriptor: receiver first for instance methods. */
    static String operands(int opcode, String owner, String descriptor) {
      return opcode == INVOKESTATIC ? descriptor : "(L" + owner + ";" + descriptor.substring(1);
    }

    static Target single(
        int opcode, String owner, String name, String desc, String helperOwner, String helperName) {
      Helper helper = new Helper(helperOwner, helperName, operands(opcode, owner, desc));
      return new Target(opcode, owner, name, desc, SINGLE, 0, helper, null, null);
    }

    static Target slotted(
        int opcode, String owner, String name, String desc, String helperName, String slotPrefix) {
      String operands = operands(opcode, owner, desc);
      return new Target(
          opcode,
          owner,
          name,
          desc,
          SLOTTED,
          0,
          new Helper(FORMATS, helperName, operands),
          slotPrefix,
          operands);
    }

    /** {@code new owner(args)} with only simple loads as arguments, replaced by a factory. */
    static Target constructor(String owner, String desc, String helperOwner, String helperName) {
      Helper factory =
          new Helper(
              helperOwner,
              helperName,
              desc.substring(0, desc.indexOf(')') + 1) + "L" + owner + ";");
      return new Target(
          INVOKESPECIAL, owner, "<init>", desc, PART, CONSTRUCTOR, factory, null, null);
    }

    static Target part(int opcode, String owner, String name, String desc, int role) {
      return new Target(opcode, owner, name, desc, PART, role, null, null, null);
    }
  }

  /** A helper method: owner, name, descriptor. */
  static final class Helper {
    final String owner;
    final String name;
    final String descriptor;

    Helper(String owner, String name, String descriptor) {
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
    }

    String key() {
      return owner + '.' + name + descriptor;
    }
  }

  private static final String STRING = "java/lang/String";
  private static final String S = "Ljava/lang/String;";
  private static final String CHARS = "Ljava/lang/CharSequence;";
  private static final String PATTERN = "java/util/regex/Pattern";
  private static final String MATCHER_CLASS = "java/util/regex/Matcher";
  private static final String DTF = "java/time/format/DateTimeFormatter";
  private static final String DECIMAL_FORMAT = "java/text/DecimalFormat";
  static final String[] TEMPORALS = {
    "java/time/LocalDate",
    "java/time/LocalDateTime",
    "java/time/LocalTime",
    "java/time/ZonedDateTime",
    "java/time/OffsetDateTime",
    "java/time/OffsetTime"
  };

  /** On JDK 8, {@code String.replace(CharSequence, CharSequence)} uses a literal Pattern. */
  static final boolean JDK_REPLACE_IS_REGEX = specificationVersion() < 9;

  /**
   * Copying a {@code DecimalFormat} prototype is only equivalent to constructing one on JDK 8 (9+
   * fills currency data lazily, which shows in serialized form), so the rewrite only pays there.
   */
  static final boolean DECIMAL_FORMAT_WINDOW = specificationVersion() < 9;

  /** JDK 9+ formats a UUID directly. */
  static final boolean UUID_TO_STRING = specificationVersion() < 9;

  /** JDK 11+ caches the simple name. */
  static final boolean SIMPLE_NAME = specificationVersion() < 11;

  static final Target[] TARGETS = buildTargets(JDK_REPLACE_IS_REGEX);

  static Target[] buildTargets(boolean includeReplace) {
    List<Target> t = new ArrayList<>();
    t.add(
        Target.single(
            INVOKEVIRTUAL, STRING, "replaceAll", "(" + S + S + ")" + S, STRINGS, "replaceAll"));
    t.add(
        Target.single(
            INVOKEVIRTUAL, STRING, "replaceFirst", "(" + S + S + ")" + S, STRINGS, "replaceFirst"));
    t.add(Target.single(INVOKEVIRTUAL, STRING, "split", "(" + S + ")[" + S, STRINGS, "split"));
    t.add(Target.single(INVOKEVIRTUAL, STRING, "split", "(" + S + "I)[" + S, STRINGS, "split"));
    t.add(Target.single(INVOKEVIRTUAL, STRING, "matches", "(" + S + ")Z", STRINGS, "matches"));
    t.add(
        Target.single(
            INVOKESTATIC, PATTERN, "matches", "(" + S + CHARS + ")Z", STRINGS, "patternMatches"));
    if (includeReplace) {
      t.add(
          Target.single(
              INVOKEVIRTUAL, STRING, "replace", "(" + CHARS + CHARS + ")" + S, STRINGS, "replace"));
    }
    t.add(
        Target.single(
            INVOKESTATIC,
            "java/net/URLEncoder",
            "encode",
            "(" + S + S + ")" + S,
            FUSED,
            "urlEncode"));
    t.add(
        Target.single(
            INVOKESTATIC,
            "java/net/URLDecoder",
            "decode",
            "(" + S + S + ")" + S,
            FUSED,
            "urlDecode"));
    t.add(
        Target.slotted(
            INVOKESTATIC,
            STRING,
            "format",
            "(" + S + "[Ljava/lang/Object;)" + S,
            "format",
            "format$"));
    t.add(
        Target.slotted(
            INVOKESTATIC,
            STRING,
            "format",
            "(Ljava/util/Locale;" + S + "[Ljava/lang/Object;)" + S,
            "format",
            "formatLocale$"));
    t.add(
        Target.slotted(
            INVOKEVIRTUAL,
            STRING,
            "formatted",
            "([Ljava/lang/Object;)" + S,
            "formatted",
            "format$"));
    t.add(Target.part(INVOKESTATIC, PATTERN, "compile", "(" + S + ")L" + PATTERN + ";", COMPILE));
    t.add(
        Target.part(
            INVOKEVIRTUAL, PATTERN, "matcher", "(" + CHARS + ")L" + MATCHER_CLASS + ";", MATCHER));
    t.add(Target.part(INVOKEVIRTUAL, MATCHER_CLASS, "matches", "()Z", MATCHER_MATCHES));
    t.add(Target.part(INVOKEVIRTUAL, MATCHER_CLASS, "find", "()Z", MATCHER_FIND));
    t.add(Target.part(INVOKEVIRTUAL, MATCHER_CLASS, "lookingAt", "()Z", MATCHER_LOOKING_AT));
    t.add(Target.part(INVOKEVIRTUAL, PATTERN, "split", "(" + CHARS + ")[" + S, PATTERN_SPLIT));
    t.add(Target.part(INVOKESTATIC, DTF, "ofPattern", "(" + S + ")L" + DTF + ";", OF_PATTERN));
    for (String temporal : TEMPORALS) {
      t.add(Target.part(INVOKEVIRTUAL, temporal, "format", "(L" + DTF + ";)" + S, TEMPORAL_FORMAT));
    }
    t.add(
        Target.part(
            INVOKEVIRTUAL,
            DTF,
            "format",
            "(Ljava/time/temporal/TemporalAccessor;)" + S,
            FORMATTER_FORMAT));
    t.add(Target.single(INVOKEVIRTUAL, STRING, "getBytes", "(" + S + ")[B", LOOKUPS, "getBytes"));
    if (UUID_TO_STRING) {
      t.add(
          Target.single(
              INVOKEVIRTUAL, "java/util/UUID", "toString", "()" + S, JDK, "uuidToString"));
    }
    if (SIMPLE_NAME) {
      t.add(
          Target.single(
              INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()" + S, JDK, "simpleName"));
    }
    String locale = "Ljava/util/Locale;";
    t.add(Target.part(INVOKEVIRTUAL, STRING, "toLowerCase", "()" + S, CASE_LOWER));
    t.add(
        Target.part(
            INVOKEVIRTUAL, STRING, "toLowerCase", "(" + locale + ")" + S, CASE_LOWER_LOCALE));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "toUpperCase", "()" + S, CASE_UPPER));
    t.add(
        Target.part(
            INVOKEVIRTUAL, STRING, "toUpperCase", "(" + locale + ")" + S, CASE_UPPER_LOCALE));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "substring", "(I)" + S, SUBSTRING_FROM));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "substring", "(II)" + S, SUBSTRING_RANGE));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "trim", "()" + S, TRIM));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "equals", "(Ljava/lang/Object;)Z", STRING_EQUALS));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "startsWith", "(" + S + ")Z", STRING_STARTS_WITH));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "endsWith", "(" + S + ")Z", STRING_ENDS_WITH));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "isEmpty", "()Z", STRING_IS_EMPTY));
    t.add(Target.part(INVOKEVIRTUAL, STRING, "length", "()I", STRING_LENGTH));
    t.add(Target.part(INVOKESTATIC, "java/lang/Integer", "parseInt", "(" + S + ")I", PARSE_INT));
    t.add(Target.part(INVOKESTATIC, "java/lang/Long", "parseLong", "(" + S + ")J", PARSE_LONG));
    t.add(
        Target.part(
            INVOKESTATIC,
            "java/lang/Integer",
            "valueOf",
            "(" + S + ")Ljava/lang/Integer;",
            VALUE_OF_INT));
    String toArray = "([Ljava/lang/Object;)[Ljava/lang/Object;";
    for (String collection :
        new String[] {"java/util/List", "java/util/Collection", "java/util/Set"}) {
      t.add(Target.part(INVOKEINTERFACE, collection, "size", "()I", COLLECTION_SIZE));
      t.add(Target.part(INVOKEINTERFACE, collection, "toArray", toArray, COLLECTION_TO_ARRAY));
    }
    t.add(Target.part(INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", COLLECTION_SIZE));
    t.add(
        Target.part(INVOKEVIRTUAL, "java/util/ArrayList", "toArray", toArray, COLLECTION_TO_ARRAY));
    t.add(
        Target.single(
            INVOKESTATIC,
            "java/nio/charset/Charset",
            "forName",
            "(" + S + ")Ljava/nio/charset/Charset;",
            LOOKUPS,
            "forName"));
    t.add(
        Target.single(
            INVOKESTATIC,
            "java/time/ZoneId",
            "of",
            "(" + S + ")Ljava/time/ZoneId;",
            LOOKUPS,
            "zoneOf"));
    t.add(Target.constructor(STRING, "([B" + S + ")V", LOOKUPS, "newString"));
    t.add(Target.constructor(STRING, "([BII" + S + ")V", LOOKUPS, "newString"));
    if (DECIMAL_FORMAT_WINDOW) {
      t.add(Target.constructor(DECIMAL_FORMAT, "(" + S + ")V", FUSED, "newDecimalFormat"));
    }
    return t.toArray(new Target[0]);
  }

  /** UTF-8 constants to look for in the constant pool before doing any more work. */
  private static final byte[][] NEEDLES = {
    ascii("replaceAll"),
    ascii("replaceFirst"),
    ascii("split"),
    ascii("matches"),
    ascii("replace"),
    ascii("encode"),
    ascii("decode"),
    ascii("format"),
    ascii("formatted"),
    ascii("compile"),
    ascii("ofPattern"),
    ascii(DECIMAL_FORMAT),
    ascii("getBytes"),
    ascii("toLowerCase"),
    ascii("toUpperCase"),
    ascii("substring"),
    ascii("trim"),
    ascii("getSimpleName"),
    ascii("java/util/UUID"),
    ascii("toArray"),
    ascii("forName"),
    ascii("java/time/ZoneId"),
    ascii("([BLjava/lang/String;)V"),
    ascii("([BIILjava/lang/String;)V")
  };

  private static final byte[] CODE = ascii("Code");
  private static final byte[] STACK_MAP_TABLE = ascii("StackMapTable");
  private static final byte[] LINE_NUMBER_TABLE = ascii("LineNumberTable");
  private static final byte[] LOCAL_VARIABLE_TABLE = ascii("LocalVariableTable");
  private static final byte[] LOCAL_VARIABLE_TYPE_TABLE = ascii("LocalVariableTypeTable");
  private static final byte[] VISIBLE_TYPE_ANNOTATIONS = ascii("RuntimeVisibleTypeAnnotations");
  private static final byte[] INVISIBLE_TYPE_ANNOTATIONS = ascii("RuntimeInvisibleTypeAnnotations");

  // PROTOTYPE diagnostics: reported at exit with -Ddd.prototype.string-rewrites.report=true
  static final AtomicLong scanned = new AtomicLong();
  static final AtomicLong candidates = new AtomicLong();
  static final AtomicLong rewritten = new AtomicLong();
  static final AtomicLong rewrittenSites = new AtomicLong();
  static final AtomicLong inLoopSites = new AtomicLong();
  static final AtomicLong nanos = new AtomicLong();
  static final ConcurrentHashMap<String, AtomicLong> sitesByRule = new ConcurrentHashMap<>();
  static final ConcurrentHashMap<String, AtomicLong> rejectedWindows = new ConcurrentHashMap<>();

  static final boolean REPORT = Boolean.getBoolean("dd.prototype.string-rewrites.report");
  static final ConcurrentLinkedQueue<String> rewrittenNames = new ConcurrentLinkedQueue<>();
  static final ThreadLocal<String> CURRENT_CLASS = new ThreadLocal<>();
  static final ConcurrentHashMap<Integer, String> slotOwners = new ConcurrentHashMap<>();

  /** PROTOTYPE report: what each format slot's site ended up doing, read reflectively at exit. */
  static String slotReport() {
    int compiled = 0;
    int disabled = 0;
    int warming = 0;
    int unused = 0;
    java.util.TreeMap<String, Integer> hot = new java.util.TreeMap<>();
    try {
      for (int chunk = 0; chunk < CHUNKS.definedChunks(); chunk++) {
        Class<?> type = Class.forName(JitSiteChunks.nameOf(chunk).replace('/', '.'), false, null);
        java.lang.reflect.Field field = type.getDeclaredField("SITES");
        field.setAccessible(true);
        Object[] sites = (Object[]) field.get(null);
        for (int i = 0; i < sites.length; i++) {
          Object site = sites[i];
          if (null == site) {
            unused++;
            continue;
          }
          java.lang.reflect.Field c = site.getClass().getDeclaredField("compiled");
          java.lang.reflect.Field d = site.getClass().getDeclaredField("disabled");
          c.setAccessible(true);
          d.setAccessible(true);
          if (null != c.get(site)) {
            compiled++;
            String owner = slotOwners.get(chunk * JitSiteChunks.SLOTS + i);
            hot.merge(String.valueOf(owner), 1, Integer::sum);
          } else if ((Boolean) d.get(site)) {
            disabled++;
          } else {
            warming++;
          }
        }
      }
    } catch (Throwable e) {
      return "slot report failed: " + e;
    }
    String cache;
    try {
      Class<?> type = Class.forName("datadog.trace.bootstrap.jit.FormatCache", false, null);
      java.lang.reflect.Field field = type.getDeclaredField("entries");
      field.setAccessible(true);
      java.util.Map<?, ?> entries = (java.util.Map<?, ?>) field.get(null);
      StringBuilder keys = new StringBuilder();
      int shown = 0;
      for (Object key : entries.keySet()) {
        if (shown++ < 12) {
          keys.append('[').append(key).append("] ");
        }
      }
      cache = "unslottedCache=" + entries.size() + " e.g. " + keys;
    } catch (Throwable e) {
      cache = "unslottedCache=? " + e;
    }
    return cache
        + " compiled="
        + compiled
        + " disabled="
        + disabled
        + " warming="
        + warming
        + " neverCalled="
        + unused
        + " compiledByClass="
        + hot;
  }

  static final boolean ANALYZE_ONLY =
      Boolean.getBoolean("dd.prototype.string-rewrites.analyze-only");

  /** PROTOTYPE: {@code false} sends every slotted call to the shared, unslotted helper. */
  static final boolean SLOTS_ENABLED =
      !"false".equals(System.getProperty("dd.prototype.string-rewrites.slots"));

  static final JitSiteChunks CHUNKS =
      new JitSiteChunks(
          new JitSiteChunks.Definer() {
            @Override
            public void define(String binaryName, byte[] bytecode) {
              datadog.instrument.classinject.ClassInjector.injectBootClass(binaryName, bytecode);
            }
          });

  static {
    if (REPORT) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(() ->
                  System.err.println(
                      "[string-rewrites] scanned="
                          + scanned
                          + " candidates="
                          + candidates
                          + " rewrittenClasses="
                          + rewritten
                          + " sites="
                          + rewrittenSites
                          + " inLoops="
                          + inLoopSites
                          + " chunks="
                          + CHUNKS.definedChunks()
                          + " transformMs="
                          + nanos.get() / 1_000_000
                          + "\n[string-rewrites] byRule="
                          + sitesByRule
                          + "\n[string-rewrites] rejectedWindows="
                          + rejectedWindows
                          + "\n[string-rewrites] slots: "
                          + slotReport()
                          + "\n[string-rewrites] classes="
                          + rewrittenNames)));
    }
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] bytecode) {
    if (null == loader
        || null != classBeingRedefined
        || null == className
        || isAgentLoader(loader)
        || skip(className)) {
      return null; // bootstrap/JDK classes, retransformations, and our own classes
    }
    long start = System.nanoTime();
    try {
      scanned.incrementAndGet();
      if (REPORT) {
        CURRENT_CLASS.set(className);
      }
      byte[] result = rewrite(bytecode);
      if (null != result && REPORT) {
        rewrittenNames.add(className);
      }
      return result;
    } catch (Throwable e) {
      return null; // never break class loading
    } finally {
      nanos.addAndGet(System.nanoTime() - start);
    }
  }

  /** The agent's own loaders (DatadogClassLoader, InstrumentationClassLoader, BootstrapProxy). */
  private static boolean isAgentLoader(ClassLoader loader) {
    return loader.getClass().getName().startsWith("datadog.");
  }

  private static boolean skip(String className) {
    return className.startsWith("datadog/")
        || className.startsWith("java/")
        || className.startsWith("jdk/")
        || className.startsWith("sun/")
        || className.startsWith("com/sun/");
  }

  /**
   * Returns the rewritten class file, or {@code null} if there's nothing to rewrite or the class
   * file can't be walked (e.g. it's truncated).
   */
  static byte[] rewrite(byte[] b) throws IOException {
    return rewrite(b, TARGETS, CHUNKS);
  }

  static byte[] rewrite(byte[] b, Target[] targets, JitSiteChunks chunks) throws IOException {
    try {
      return doRewrite(b, targets, chunks);
    } catch (IndexOutOfBoundsException e) {
      return null;
    }
  }

  /** One planned change: {@code length} bytes at offset {@code at} in the class file. */
  private static final class Patch {
    final int at;
    final int length;
    final int prefixFrom; // offset in the class file of a load instruction to keep first, or -1
    final int prefixLength;
    final int lead; // an opcode to emit before the kept instruction, or -1
    final Helper helper;
    final boolean inLoop;

    Patch(int at, int length, int prefixFrom, int prefixLength, Helper helper, boolean inLoop) {
      this(at, length, -1, prefixFrom, prefixLength, helper, inLoop);
    }

    /** Raises the max_stack field at {@code at} (in the class file) by one. */
    static Patch maxStackBump(int at) {
      return new Patch(at, 0, -1, -1, 0, null, false);
    }

    Patch(
        int at,
        int length,
        int lead,
        int prefixFrom,
        int prefixLength,
        Helper helper,
        boolean inLoop) {
      this.lead = lead;
      this.at = at;
      this.length = length;
      this.prefixFrom = prefixFrom;
      this.prefixLength = prefixLength;
      this.helper = helper;
      this.inLoop = inLoop;
    }
  }

  /** What the walk of the constant pool found. */
  private static final class Pool {
    final byte[] b;
    final int count;
    final int[] offsets;
    final int end;
    final int[] targetForEntry; // Methodref index -> target index, or -1
    int code = -1;
    int stackMapTable = -1;
    int lineNumberTable = -1;
    int localVariableTable = -1;
    int localVariableTypeTable = -1;
    int visibleTypeAnnotations = -1;
    int invisibleTypeAnnotations = -1;

    Pool(byte[] b, int count, int[] offsets, int end, int[] targetForEntry) {
      this.b = b;
      this.count = count;
      this.offsets = offsets;
      this.end = end;
      this.targetForEntry = targetForEntry;
    }

    int target(int index) {
      return index > 0 && index < targetForEntry.length ? targetForEntry[index] : -1;
    }

    boolean isString(int index) {
      return index > 0 && index < count && offsets[index] != 0 && b[offsets[index]] == 8;
    }
  }

  private static byte[] doRewrite(byte[] b, Target[] targets, JitSiteChunks chunks)
      throws IOException {
    if (b.length < 10 || readInt(b, 0) != 0xCAFEBABE) {
      return null;
    }
    int count = readU2(b, 8);
    int[] offsets = new int[count];
    int offset = 10;
    boolean candidate = false;
    for (int i = 1; i < count; i++) {
      offsets[i] = offset;
      int tag = b[offset];
      if (tag == 1) {
        int length = readU2(b, offset + 1);
        if (!candidate) {
          for (byte[] needle : NEEDLES) {
            if (length == needle.length && regionEquals(b, offset + 3, needle)) {
              candidate = true;
              break;
            }
          }
        }
        offset += 3 + length;
      } else {
        int size = constantSize(tag);
        if (size < 0) {
          return null; // unknown constant-pool tag
        }
        offset += size;
        if (tag == 5 || tag == 6) {
          i++;
        }
      }
    }
    if (!candidate) {
      return null; // most classes stop here, after one walk of the constant pool
    }
    candidates.incrementAndGet();

    boolean anyTarget = false;
    int[] targetForEntry = new int[count];
    Arrays.fill(targetForEntry, -1);
    for (int i = 1; i < count; i++) {
      int o = offsets[i];
      if (o == 0) {
        continue;
      }
      if (b[o] == 10 || b[o] == 11) { // Methodref, InterfaceMethodref
        int t = matchTarget(b, offsets, o, targets);
        if (t >= 0) {
          targetForEntry[i] = t;
          anyTarget = true;
        }
      }
    }
    if (!anyTarget) {
      return null;
    }
    Pool pool = new Pool(b, count, offsets, offset, targetForEntry);
    pool.code = findUtf8(b, offsets, count, CODE);
    if (pool.code < 0) {
      return null;
    }
    pool.stackMapTable = findUtf8(b, offsets, count, STACK_MAP_TABLE);
    pool.lineNumberTable = findUtf8(b, offsets, count, LINE_NUMBER_TABLE);
    pool.localVariableTable = findUtf8(b, offsets, count, LOCAL_VARIABLE_TABLE);
    pool.localVariableTypeTable = findUtf8(b, offsets, count, LOCAL_VARIABLE_TYPE_TABLE);
    pool.visibleTypeAnnotations = findUtf8(b, offsets, count, VISIBLE_TYPE_ANNOTATIONS);
    pool.invisibleTypeAnnotations = findUtf8(b, offsets, count, INVISIBLE_TYPE_ANNOTATIONS);

    List<Patch> patches = new ArrayList<>();
    int p = pool.end + 6; // access, this, super
    int interfaces = readU2(b, p);
    p += 2 + 2 * interfaces;
    p = skipMembers(b, p, null, null, null, null); // fields
    p = skipMembers(b, p, pool, targets, chunks, patches); // methods
    if (p < 0 || patches.isEmpty()) {
      return null;
    }

    // append the helpers' constant-pool entries
    ByteArrayOutputStream appended = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(appended);
    int[] next = {count};
    Map<String, Integer> utf8s = new HashMap<>();
    Map<String, Integer> classes = new HashMap<>();
    Map<String, Integer> methodrefs = new HashMap<>();
    for (Patch patch : patches) {
      Helper helper = patch.helper;
      if (null == helper || methodrefs.containsKey(helper.key())) {
        continue;
      }
      Integer classIndex = classes.get(helper.owner);
      if (null == classIndex) {
        int name = utf8(out, utf8s, next, helper.owner);
        classIndex = next[0]++;
        out.writeByte(7);
        out.writeShort(name);
        classes.put(helper.owner, classIndex);
      }
      int name = utf8(out, utf8s, next, helper.name);
      int descriptor = utf8(out, utf8s, next, helper.descriptor);
      int nameAndType = next[0]++;
      out.writeByte(12);
      out.writeShort(name);
      out.writeShort(descriptor);
      int methodref = next[0]++;
      out.writeByte(10);
      out.writeShort(classIndex);
      out.writeShort(nameAndType);
      methodrefs.put(helper.key(), methodref);
    }
    if (next[0] > 0xFFFF) {
      return null; // constant pool would overflow
    }
    out.flush();
    byte[] extra = appended.toByteArray();

    if (ANALYZE_ONLY) {
      return null; // PROTOTYPE: isolates the cost of running rewritten sites from scanning
    }
    byte[] result = new byte[b.length + extra.length];
    System.arraycopy(b, 0, result, 0, pool.end);
    writeU2(result, 8, next[0]);
    System.arraycopy(extra, 0, result, pool.end, extra.length);
    System.arraycopy(b, pool.end, result, pool.end + extra.length, b.length - pool.end);
    for (Patch patch : patches) {
      int at = patch.at + extra.length;
      if (null == patch.helper) {
        writeU2(result, at, readU2(b, patch.at) + 1); // max_stack
        continue;
      }
      Arrays.fill(result, at, at + patch.length, (byte) NOP);
      int w = at;
      if (patch.lead >= 0) {
        result[w++] = (byte) patch.lead;
      }
      if (patch.prefixFrom >= 0) {
        System.arraycopy(b, patch.prefixFrom, result, w, patch.prefixLength);
        w += patch.prefixLength;
      }
      result[w] = (byte) INVOKESTATIC;
      writeU2(result, w + 1, methodrefs.get(patch.helper.key()));
    }
    rewritten.incrementAndGet();
    for (Patch patch : patches) {
      if (null == patch.helper) {
        continue;
      }
      rewrittenSites.incrementAndGet();
      if (patch.inLoop) {
        inLoopSites.incrementAndGet();
      }
      int dollar = patch.helper.name.indexOf('$');
      count(
          sitesByRule,
          dollar > 0 ? "slotted:" + patch.helper.name.substring(0, dollar) : patch.helper.name);
    }
    return result;
  }

  private static int utf8(
      DataOutputStream out, Map<String, Integer> utf8s, int[] next, String value)
      throws IOException {
    Integer index = utf8s.get(value);
    if (null == index) {
      index = next[0]++;
      out.writeByte(1);
      out.writeUTF(value); // modified UTF-8 with a u2 length, as in class files
      utf8s.put(value, index);
    }
    return index;
  }

  private static void count(ConcurrentHashMap<String, AtomicLong> map, String key) {
    AtomicLong counter = map.get(key);
    if (null == counter) {
      AtomicLong created = new AtomicLong();
      counter = map.putIfAbsent(key, created);
      if (null == counter) {
        counter = created;
      }
    }
    counter.incrementAndGet();
  }

  /** Which target a Methodref entry matches, or -1. */
  private static int matchTarget(byte[] b, int[] offsets, int methodref, Target[] targets) {
    int classOffset = offsets[readU2(b, methodref + 1)];
    int natOffset = offsets[readU2(b, methodref + 3)];
    if (b[classOffset] != 7 || b[natOffset] != 12) {
      return -1;
    }
    int owner = offsets[readU2(b, classOffset + 1)];
    int name = offsets[readU2(b, natOffset + 1)];
    int descriptor = offsets[readU2(b, natOffset + 3)];
    for (int i = 0; i < targets.length; i++) {
      Target target = targets[i];
      if (utf8Equals(b, name, target.name)
          && utf8Equals(b, owner, target.owner)
          && utf8Equals(b, descriptor, target.descriptor)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Skips a fields or methods table; for methods, scans each Code attribute. Returns the offset
   * after the table, or -1 if the bytecode can't be walked.
   */
  private static int skipMembers(
      byte[] b, int p, Pool pool, Target[] targets, JitSiteChunks chunks, List<Patch> patches) {
    int members = readU2(b, p);
    p += 2;
    for (int m = 0; m < members; m++) {
      p += 6; // access, name, descriptor
      int attributes = readU2(b, p);
      p += 2;
      for (int a = 0; a < attributes; a++) {
        int attributeName = readU2(b, p);
        int length = readInt(b, p + 2);
        int start = p + 6;
        if (null != pool && attributeName == pool.code) {
          if (!scanCode(b, start, pool, targets, chunks, patches)) {
            return -1;
          }
        }
        p = start + length;
      }
    }
    return p;
  }

  /** Scans one Code attribute (starting at max_stack), adding the patches for its call sites. */
  private static boolean scanCode(
      byte[] b,
      int attribute,
      Pool pool,
      Target[] targets,
      JitSiteChunks chunks,
      List<Patch> patches) {
    int codeLength = readInt(b, attribute + 4);
    int code = attribute + 8;

    // instruction starts (with a sentinel at codeLength), and whether anything here is a target
    int[] starts = new int[codeLength + 1];
    int n = 0;
    boolean anyTarget = false;
    boolean anyWindow = false;
    int pc = 0;
    while (pc < codeLength) {
      int opcode = b[code + pc] & 0xFF;
      starts[n++] = pc;
      if (isInvoke(opcode)) {
        int t = pool.target(readU2(b, code + pc + 1));
        if (t >= 0) {
          int role = targets[t].role;
          boolean beginsWindow = startsWindow(role);
          anyTarget |= targets[t].kind != PART || beginsWindow;
          anyWindow |= beginsWindow;
        }
      }
      int length = instructionLength(b, code, pc, opcode);
      if (length <= 0) {
        return false;
      }
      pc += length;
    }
    if (pc != codeLength) {
      return false;
    }
    starts[n] = codeLength;
    if (!anyTarget) {
      return true;
    }

    // back-edges (loops), and offsets a window must not contain
    List<int[]> loops = new ArrayList<>();
    byte[] boundary = anyWindow ? new byte[codeLength + 1] : null;
    for (int k = 0; k < n; k++) {
      int at = starts[k];
      int opcode = b[code + at] & 0xFF;
      if ((opcode >= 0x99 && opcode <= 0xA8) || opcode == 0xC6 || opcode == 0xC7) {
        branch(at, at + (short) readU2(b, code + at + 1), loops, boundary);
      } else if (opcode == 0xC8 || opcode == 0xC9) {
        branch(at, at + readInt(b, code + at + 1), loops, boundary);
      } else if (opcode == 0xAA || opcode == 0xAB) {
        int q = (at + 4) & ~3;
        branch(at, at + readInt(b, code + q), loops, boundary);
        if (opcode == 0xAA) {
          int low = readInt(b, code + q + 4);
          int high = readInt(b, code + q + 8);
          for (int i = 0; i <= high - low; i++) {
            branch(at, at + readInt(b, code + q + 12 + 4 * i), loops, boundary);
          }
        } else {
          int pairs = readInt(b, code + q + 4);
          for (int i = 0; i < pairs; i++) {
            branch(at, at + readInt(b, code + q + 12 + 8 * i), loops, boundary);
          }
        }
      }
    }
    boolean windowsAllowed = anyWindow;
    if (anyWindow) {
      int table = code + codeLength;
      int entries = readU2(b, table);
      for (int i = 0; i < entries; i++) {
        int e = table + 2 + 8 * i;
        mark(boundary, readU2(b, e), CONTROL); // start_pc
        mark(boundary, readU2(b, e + 2), CONTROL); // end_pc
        mark(boundary, readU2(b, e + 4), CONTROL); // handler_pc
      }
      int ap = table + 2 + 8 * entries;
      int attributes = readU2(b, ap);
      ap += 2;
      for (int a = 0; a < attributes; a++) {
        int name = readU2(b, ap);
        int length = readInt(b, ap + 2);
        int start = ap + 6;
        if (name == pool.stackMapTable) {
          if (!markFrames(b, start, boundary)) {
            return false;
          }
        } else if (name == pool.lineNumberTable) {
          int lines = readU2(b, start);
          for (int i = 0; i < lines; i++) {
            mark(boundary, readU2(b, start + 2 + 4 * i), DEBUG);
          }
        } else if (name == pool.localVariableTable || name == pool.localVariableTypeTable) {
          int locals = readU2(b, start);
          for (int i = 0; i < locals; i++) {
            int from = readU2(b, start + 2 + 10 * i);
            mark(boundary, from, DEBUG);
            mark(boundary, from + readU2(b, start + 4 + 10 * i), DEBUG);
          }
        } else if (name == pool.visibleTypeAnnotations || name == pool.invisibleTypeAnnotations) {
          windowsAllowed = false; // their offsets would need checking too; rare, so skip
        }
        ap = start + length;
      }
    }

    Method m =
        new Method(b, attribute, code, starts, n, pool, targets, boundary, windowsAllowed, patches);
    for (int k = 0; k < n; k++) {
      int at = starts[k];
      int opcode = b[code + at] & 0xFF;
      Target target = m.targetAt(k);
      boolean inLoop = inLoop(loops, at);
      if (null != target && target.kind == SINGLE) {
        patches.add(new Patch(code + at, 3, -1, 0, target.helper, inLoop));
      } else if (null != target && target.kind == SLOTTED) {
        Helper helper = target.helper;
        int slot = ANALYZE_ONLY || !SLOTS_ENABLED ? -1 : chunks.allocate();
        if (slot >= 0 && REPORT) {
          slotOwners.put(slot, String.valueOf(CURRENT_CLASS.get()));
        }
        if (slot >= 0) {
          helper =
              new Helper(
                  JitSiteChunks.chunkName(slot),
                  target.slotPrefix + (slot % JitSiteChunks.SLOTS),
                  target.slotDescriptor);
        }
        patches.add(new Patch(code + at, 3, -1, 0, helper, inLoop));
      } else {
        int covered = m.matchWindow(k, opcode, target, inLoop);
        if (covered > 0) {
          k += covered - 1; // skip the window's other instructions
        }
      }
    }
    return true;
  }

  /** One method's decoded instructions, for matching windows. */
  private static final class Method {
    final byte[] b;
    final int code;
    final int[] starts;
    final int n;
    final Pool pool;
    final Target[] targets;
    final byte[] boundary;
    final boolean windowsAllowed;
    final List<Patch> patches;
    final int maxStackAt; // offset of this method's max_stack field
    boolean maxStackRaised;

    Method(
        byte[] b,
        int maxStackAt,
        int code,
        int[] starts,
        int n,
        Pool pool,
        Target[] targets,
        byte[] boundary,
        boolean windowsAllowed,
        List<Patch> patches) {
      this.b = b;
      this.code = code;
      this.starts = starts;
      this.n = n;
      this.pool = pool;
      this.targets = targets;
      this.boundary = boundary;
      this.windowsAllowed = windowsAllowed;
      this.patches = patches;
      this.maxStackAt = maxStackAt;
    }

    /** The target called by instruction {@code k}, if it's an invoke with the expected opcode. */
    Target targetAt(int k) {
      if (k >= n) {
        return null;
      }
      int opcode = b[code + starts[k]] & 0xFF;
      if (!isInvoke(opcode)) {
        return null;
      }
      int t = pool.target(readU2(b, code + starts[k] + 1));
      return t >= 0 && targets[t].opcode == opcode ? targets[t] : null;
    }

    /** Length of an {@code aload} at instruction {@code k}, or 0. */
    int loadLength(int k) {
      if (k >= n) {
        return 0;
      }
      int opcode = b[code + starts[k]] & 0xFF;
      if (opcode >= 0x2A && opcode <= 0x2D) {
        return 1; // aload_0 .. aload_3
      }
      return opcode == ALOAD ? 2 : 0;
    }

    /**
     * Length of an instruction at {@code k} that only pushes a local or a constant (int, float or
     * String), with no side effects, or 0.
     */
    int pureLoadLength(int k) {
      if (k >= n) {
        return 0;
      }
      int at = code + starts[k];
      int opcode = b[at] & 0xFF;
      if (opcode >= 0x01 && opcode <= 0x08) {
        return 1; // aconst_null, iconst_*
      }
      if ((opcode >= 0x1A && opcode <= 0x1D) || (opcode >= 0x2A && opcode <= 0x2D)) {
        return 1; // iload_n, aload_n
      }
      switch (opcode) {
        case 0x10: // bipush
        case 0x15: // iload
        case ALOAD:
          return 2;
        case 0x11: // sipush
          return 3;
        case LDC:
          return isPlainConstant(b[at + 1] & 0xFF) ? 2 : 0;
        case LDC_W:
          return isPlainConstant(readU2(b, at + 1)) ? 3 : 0;
        default:
          return 0;
      }
    }

    /** An int, float or String constant (not a class, method handle or dynamic constant). */
    private boolean isPlainConstant(int index) {
      if (index <= 0 || index >= pool.count || pool.offsets[index] == 0) {
        return false;
      }
      int tag = b[pool.offsets[index]];
      return tag == 3 || tag == 4 || tag == 8;
    }

    /** Whether constant {@code index} is a Class entry naming {@code name}. */
    private boolean isClass(int index, String name) {
      if (index <= 0 || index >= pool.count || pool.offsets[index] == 0) {
        return false;
      }
      int o = pool.offsets[index];
      return b[o] == 7 && utf8Equals(b, pool.offsets[readU2(b, o + 1)], name);
    }

    /**
     * Tries to rewrite the window starting at instruction {@code k}. Returns the number of
     * instructions it covers, or 0.
     */
    int matchWindow(int k, int opcode, Target first, boolean inLoop) {
      if (null == first && k > 0 && loadLength(k) > 0 && sameInstruction(k - 1, k) && k + 3 < n) {
        // c (kept), then c, c.size(), anewarray T, c.toArray(array): one helper call that is given
        // a fresh zero-length T[] instead (iconst_0, anewarray T)
        Target size = targetAt(k + 1);
        Target toArray = targetAt(k + 3);
        if (null != size
            && size.role == COLLECTION_SIZE
            && (b[code + starts[k + 2]] & 0xFF) == ANEWARRAY
            && null != toArray
            && toArray.role == COLLECTION_TO_ARRAY) {
          return window(
              k,
              4,
              ICONST_0,
              k + 2,
              3,
              new Helper(
                  COLLECTIONS,
                  "toArrayPresized",
                  "(Ljava/util/Collection;[Ljava/lang/Object;)[Ljava/lang/Object;"),
              inLoop,
              "toArrayPresized");
        }
      }
      if (null != first && first.role == COMPILE) {
        // Pattern.compile(r), load s, .matcher(s), .matches()/.find()/.lookingAt() | .split(s)
        int load = loadLength(k + 1);
        Target second = load > 0 ? targetAt(k + 2) : null;
        if (null != second && second.role == MATCHER) {
          Target third = targetAt(k + 3);
          String helper = null;
          if (null != third && third.role == MATCHER_MATCHES) {
            helper = "compileMatches";
          } else if (null != third && third.role == MATCHER_FIND) {
            helper = "compileFind";
          } else if (null != third && third.role == MATCHER_LOOKING_AT) {
            helper = "compileLookingAt";
          }
          if (null != helper) {
            return window(
                k,
                4,
                k + 1,
                load,
                new Helper(FUSED, helper, "(" + S + CHARS + ")Z"),
                inLoop,
                "compileMatcher");
          }
        } else if (null != second && second.role == PATTERN_SPLIT) {
          return window(
              k,
              3,
              k + 1,
              load,
              new Helper(FUSED, "compileSplit", "(" + S + CHARS + ")[" + S),
              inLoop,
              "compileSplit");
        }
        return 0;
      }
      if (null != first && first.role >= CASE_LOWER && first.role <= CASE_UPPER_LOCALE) {
        // s.toLowerCase([locale]) then equals/startsWith/endsWith, or x.equals(s.toLowerCase())
        boolean upper = first.role >= CASE_UPPER;
        String locale =
            first.role == CASE_LOWER_LOCALE || first.role == CASE_UPPER_LOCALE
                ? "Ljava/util/Locale;"
                : "";
        Target second = targetAt(k + 1);
        if (null != second && second.role == STRING_EQUALS) {
          return window(
              k,
              2,
              -1,
              0,
              new Helper(
                  FUSIONS, upper ? "equalsUpper" : "equalsLower", "(" + S + S + locale + ")Z"),
              inLoop,
              "caseEqualsReceiver");
        }
        int load = pureLoadLength(k + 1);
        Target third = load > 0 ? targetAt(k + 2) : null;
        String op = null == third ? null : comparison(third.role);
        if (null != op) {
          return window(
              k,
              3,
              k + 1,
              load,
              new Helper(
                  FUSIONS,
                  (upper ? "upper" : "lower") + op,
                  "(" + S + locale + comparisonArgument(third.role) + ")Z"),
              inLoop,
              "case" + op);
        }
        return 0;
      }
      if (null != first && (first.role == SUBSTRING_FROM || first.role == SUBSTRING_RANGE)) {
        // s.substring(..) then equals/startsWith/endsWith, or x.equals(s.substring(..))
        String indexes = first.role == SUBSTRING_RANGE ? "II" : "I";
        Target second = targetAt(k + 1);
        if (null != second
            && (second.role == PARSE_INT
                || second.role == PARSE_LONG
                || second.role == VALUE_OF_INT)) {
          // Integer.parseInt(s.substring(..)), Long.parseLong(..), Integer.valueOf(..)
          String name =
              second.role == PARSE_INT
                  ? "parseIntSubstring"
                  : second.role == PARSE_LONG ? "parseLongSubstring" : "valueOfSubstring";
          String result =
              second.role == PARSE_INT
                  ? "I"
                  : second.role == PARSE_LONG ? "J" : "Ljava/lang/Integer;";
          return window(
              k,
              2,
              -1,
              0,
              new Helper(NUMBERS, name, "(" + S + indexes + ")" + result),
              inLoop,
              name);
        }
        if (null != second && second.role == STRING_EQUALS) {
          return window(
              k,
              2,
              -1,
              0,
              new Helper(FUSIONS, "equalsSubstring", "(" + S + S + indexes + ")Z"),
              inLoop,
              "substringEqualsReceiver");
        }
        int load = pureLoadLength(k + 1);
        Target third = load > 0 ? targetAt(k + 2) : null;
        String op = null == third ? null : comparison(third.role);
        if (null != op) {
          return window(
              k,
              3,
              k + 1,
              load,
              new Helper(
                  FUSIONS,
                  "substring" + op,
                  "(" + S + indexes + comparisonArgument(third.role) + ")Z"),
              inLoop,
              "substring" + op);
        }
        return 0;
      }
      if (null != first && first.role == TRIM) {
        Target second = targetAt(k + 1);
        if (null != second && second.role == STRING_IS_EMPTY) {
          return window(
              k,
              2,
              -1,
              0,
              new Helper(FUSIONS, "trimIsEmpty", "(" + S + ")Z"),
              inLoop,
              "trimIsEmpty");
        }
        if (null != second && second.role == STRING_LENGTH) {
          return window(
              k, 2, -1, 0, new Helper(FUSIONS, "trimLength", "(" + S + ")I"), inLoop, "trimLength");
        }
        return 0;
      }
      if (null != first && first.role == OF_PATTERN) {
        Target second = targetAt(k + 1);
        if (null != second && second.role == TEMPORAL_FORMAT) {
          // t and the pattern are on the stack: ofPattern(pattern), then t.format(formatter)
          return window(
              k,
              2,
              -1,
              0,
              new Helper(FUSED, "temporalFormat", "(L" + second.owner + ";" + S + ")" + S),
              inLoop,
              "temporalFormat");
        }
        int load = loadLength(k + 1);
        Target third = load > 0 ? targetAt(k + 2) : null;
        if (null != third && third.role == FORMATTER_FORMAT) {
          return window(
              k,
              3,
              k + 1,
              load,
              new Helper(
                  FUSED, "formatterFormat", "(" + S + "Ljava/time/temporal/TemporalAccessor;)" + S),
              inLoop,
              "formatterFormat");
        }
        return 0;
      }
      if (opcode == NEW && k + 2 < n && (b[code + starts[k + 1]] & 0xFF) == DUP) {
        // new C, dup, simple loads of the arguments, invokespecial C.<init>(args)
        int j = k + 2;
        int argBytes = 0;
        int length;
        while (j < n && j < k + 6 && (length = pureLoadLength(j)) > 0) {
          argBytes += length;
          j++;
        }
        Target init = targetAt(j);
        if (null != init
            && init.role == CONSTRUCTOR
            && j - (k + 2) == parameterCount(init.descriptor)
            && isClass(readU2(b, code + starts[k] + 1), init.owner)) {
          return window(k, j - k + 1, k + 2, argBytes, init.helper, inLoop, init.helper.name);
        }
      }
      return 0;
    }

    /**
     * Adds a patch for {@code instructions} instructions from {@code k}, keeping instruction {@code
     * prefix} (a load, {@code prefixLength} bytes) first, if no offset strictly inside the window
     * is observable. Returns the number of instructions covered, or 0.
     */
    int window(
        int k,
        int instructions,
        int prefix,
        int prefixLength,
        Helper helper,
        boolean inLoop,
        String kind) {
      return window(k, instructions, -1, prefix, prefixLength, helper, inLoop, kind);
    }

    /** Whether instructions i and j are byte-for-byte the same (e.g. two loads of one local). */
    boolean sameInstruction(int i, int j) {
      int length = starts[i + 1] - starts[i];
      if (length != starts[j + 1] - starts[j]) {
        return false;
      }
      for (int o = 0; o < length; o++) {
        if (b[code + starts[i] + o] != b[code + starts[j] + o]) {
          return false;
        }
      }
      return true;
    }

    int window(
        int k,
        int instructions,
        int lead,
        int prefix,
        int prefixLength,
        Helper helper,
        boolean inLoop,
        String kind) {
      int start = starts[k];
      int end = starts[k + instructions];
      if (!windowsAllowed) {
        count(rejectedWindows, kind + ":typeAnnotations");
        return 0;
      }
      byte inside = 0;
      for (int o = start + 1; o < end; o++) {
        inside |= boundary[o];
      }
      if (inside != 0) {
        // debug-only boundaries (line numbers, local ranges) are counted apart: they could be kept
        // valid by putting the fused code at the end, if changing reported lines were acceptable
        count(rejectedWindows, kind + ((inside & CONTROL) != 0 ? ":control" : ":debugInfo"));
        return 0;
      }
      if ((lead >= 0 ? 1 : 0) + prefixLength + 3 > end - start) {
        return 0; // never grow code (can't happen for the shapes above)
      }
      Target call = targetAt(k);
      boolean growsStack = prefix > k && null != call && operandSlots(call) >= 2;
      if (growsStack && !maxStackRaised) {
        if (readU2(b, maxStackAt) >= 0xFFFF) {
          count(rejectedWindows, kind + ":maxStack");
          return 0;
        }
        patches.add(Patch.maxStackBump(maxStackAt));
        maxStackRaised = true;
      }
      patches.add(
          new Patch(
              code + start,
              end - start,
              lead,
              prefix >= 0 ? code + starts[prefix] : -1,
              prefixLength,
              helper,
              inLoop));
      return instructions;
    }
  }

  /** Stack slots a call takes: the receiver (unless static) and its parameters. */
  static int operandSlots(Target target) {
    int slots = target.opcode == INVOKESTATIC ? 0 : 1;
    String d = target.descriptor;
    for (int i = 1; d.charAt(i) != ')'; i++) {
      char c = d.charAt(i);
      if (c == 'J' || c == 'D') {
        slots += 2;
        continue;
      }
      while (c == '[') {
        c = d.charAt(++i);
      }
      if (c == 'L') {
        i = d.indexOf(';', i);
      }
      slots++;
    }
    return slots;
  }

  static boolean isInvoke(int opcode) {
    return opcode == INVOKEVIRTUAL
        || opcode == INVOKESTATIC
        || opcode == INVOKESPECIAL
        || opcode == INVOKEINTERFACE;
  }

  /** The helper suffix for a String comparison that can end a window, or null. */
  static String comparison(int role) {
    switch (role) {
      case STRING_EQUALS:
        return "Equals";
      case STRING_STARTS_WITH:
        return "StartsWith";
      case STRING_ENDS_WITH:
        return "EndsWith";
      default:
        return null;
    }
  }

  /** The type of that comparison's argument. */
  static String comparisonArgument(int role) {
    return role == STRING_EQUALS ? "Ljava/lang/Object;" : "Ljava/lang/String;";
  }

  /** Number of parameters in a method descriptor (all single-slot types here). */
  static int parameterCount(String descriptor) {
    int count = 0;
    int i = 1;
    while (descriptor.charAt(i) != ')') {
      char c = descriptor.charAt(i);
      while (c == '[') {
        c = descriptor.charAt(++i);
      }
      if (c == 'L') {
        i = descriptor.indexOf(';', i);
      }
      i++;
      count++;
    }
    return count;
  }

  private static void branch(int from, int to, List<int[]> loops, byte[] boundary) {
    if (to <= from) {
      loops.add(new int[] {to, from});
    }
    if (null != boundary) {
      mark(boundary, to, CONTROL);
    }
  }

  /** Something can jump to, or a frame describes, this offset. */
  private static final byte CONTROL = 1;

  /** Debug info (a line number or local variable range) starts at this offset. */
  private static final byte DEBUG = 2;

  private static void mark(byte[] boundary, int offset, byte kind) {
    if (offset >= 0 && offset < boundary.length) {
      boundary[offset] |= kind;
    }
  }

  private static boolean inLoop(List<int[]> loops, int pc) {
    for (int[] loop : loops) {
      if (pc >= loop[0] && pc <= loop[1]) {
        return true;
      }
    }
    return false;
  }

  /** Marks the offset of every stack-map frame. Returns false if the table can't be walked. */
  private static boolean markFrames(byte[] b, int p, byte[] boundary) {
    int frames = readU2(b, p);
    p += 2;
    int offset = -1;
    for (int i = 0; i < frames; i++) {
      int type = b[p++] & 0xFF;
      int delta;
      if (type <= 63) {
        delta = type; // same_frame
      } else if (type <= 127) {
        delta = type - 64; // same_locals_1_stack_item
        p = skipVerificationType(b, p);
      } else if (type == 247) {
        delta = readU2(b, p); // same_locals_1_stack_item_extended
        p = skipVerificationType(b, p + 2);
      } else if (type >= 248 && type <= 251) {
        delta = readU2(b, p); // chop, same_frame_extended
        p += 2;
      } else if (type >= 252 && type <= 254) {
        delta = readU2(b, p); // append
        p += 2;
        for (int j = 0; j < type - 251; j++) {
          p = skipVerificationType(b, p);
        }
      } else if (type == 255) {
        delta = readU2(b, p); // full_frame
        p += 2;
        int locals = readU2(b, p);
        p += 2;
        for (int j = 0; j < locals; j++) {
          p = skipVerificationType(b, p);
        }
        int stack = readU2(b, p);
        p += 2;
        for (int j = 0; j < stack; j++) {
          p = skipVerificationType(b, p);
        }
      } else {
        return false; // reserved frame type
      }
      offset = offset < 0 ? delta : offset + delta + 1;
      mark(boundary, offset, CONTROL);
    }
    return true;
  }

  private static int skipVerificationType(byte[] b, int p) {
    int tag = b[p] & 0xFF;
    return tag == 7 || tag == 8 ? p + 3 : p + 1; // Object and Uninitialized carry a u2
  }

  /** Size of a constant-pool entry other than UTF-8, or -1 for an unknown tag. */
  static int constantSize(int tag) {
    switch (tag) {
      case 3:
      case 4:
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        return 5;
      case 5:
      case 6:
        return 9;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        return 3;
      case 15:
        return 4;
      default:
        return -1;
    }
  }

  /** Length in bytes of the instruction at {@code pc}, or -1 for an unknown opcode. */
  static int instructionLength(byte[] b, int codeStart, int pc, int opcode) {
    if (opcode <= 0x0F) return 1; // nop .. dconst_1
    switch (opcode) {
      case 0x10:
        return 2; // bipush
      case 0x11:
        return 3; // sipush
      case 0x12:
        return 2; // ldc
      case 0x13:
      case 0x14:
        return 3; // ldc_w, ldc2_w
      case 0x84:
        return 3; // iinc
      case 0xA9:
        return 2; // ret
      case 0xBC:
        return 2; // newarray
      case 0xB9:
      case 0xBA:
        return 5; // invokeinterface, invokedynamic
      case 0xC5:
        return 4; // multianewarray
      case 0xC8:
      case 0xC9:
        return 5; // goto_w, jsr_w
      case 0xC4: // wide
        return (b[codeStart + pc + 1] & 0xFF) == 0x84 ? 6 : 4;
      case 0xAA: // tableswitch
        {
          int p = (pc + 4) & ~3;
          int low = readInt(b, codeStart + p + 4);
          int high = readInt(b, codeStart + p + 8);
          return p - pc + 12 + 4 * (high - low + 1);
        }
      case 0xAB: // lookupswitch
        {
          int p = (pc + 4) & ~3;
          int pairs = readInt(b, codeStart + p + 4);
          return p - pc + 8 + 8 * pairs;
        }
      default:
        break;
    }
    if (opcode >= 0x15 && opcode <= 0x19) return 2; // xload index
    if (opcode >= 0x36 && opcode <= 0x3A) return 2; // xstore index
    if (opcode >= 0x1A && opcode <= 0x35) return 1; // xload_n, xaload
    if (opcode >= 0x3B && opcode <= 0x83) return 1; // xstore_n .. lxor
    if (opcode >= 0x85 && opcode <= 0x98) return 1; // conversions, comparisons
    if (opcode >= 0x99 && opcode <= 0xA8) return 3; // if*, goto, jsr
    if (opcode >= 0xAC && opcode <= 0xB1) return 1; // returns
    if (opcode >= 0xB2 && opcode <= 0xB8) return 3; // field access, invokes
    if (opcode == 0xBB || opcode == 0xBD || opcode == 0xC0 || opcode == 0xC1) return 3;
    if (opcode == 0xBE || opcode == 0xBF || opcode == 0xC2 || opcode == 0xC3) return 1;
    if (opcode == 0xC6 || opcode == 0xC7) return 3; // ifnull, ifnonnull
    return -1;
  }

  private static int findUtf8(byte[] b, int[] offsets, int count, byte[] value) {
    for (int i = 1; i < count; i++) {
      int o = offsets[i];
      if (o != 0
          && b[o] == 1
          && readU2(b, o + 1) == value.length
          && regionEquals(b, o + 3, value)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean utf8Equals(byte[] b, int offset, String value) {
    if (b[offset] != 1) {
      return false;
    }
    int length = readU2(b, offset + 1);
    if (length != value.length()) {
      return false; // all targets are ASCII, so bytes == chars
    }
    for (int i = 0; i < length; i++) {
      if (b[offset + 3 + i] != (byte) value.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static boolean regionEquals(byte[] b, int offset, byte[] value) {
    for (int i = 0; i < value.length; i++) {
      if (b[offset + i] != value[i]) {
        return false;
      }
    }
    return true;
  }

  private static int specificationVersion() {
    try {
      String version = System.getProperty("java.specification.version", "");
      return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    } catch (RuntimeException e) {
      return 8; // assume the older behavior
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  static int readU2(byte[] b, int offset) {
    return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
  }

  static int readInt(byte[] b, int offset) {
    return ((b[offset] & 0xFF) << 24)
        | ((b[offset + 1] & 0xFF) << 16)
        | ((b[offset + 2] & 0xFF) << 8)
        | (b[offset + 3] & 0xFF);
  }

  private static void writeU2(byte[] b, int offset, int value) {
    b[offset] = (byte) (value >>> 8);
    b[offset + 1] = (byte) value;
  }
}
