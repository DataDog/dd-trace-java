package datadog.trace.agent.tooling.jit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PROTOTYPE: rewrites calls to regex-based {@link String} methods (and {@code Pattern.matches})
 * into calls to {@code datadog.trace.bootstrap.jit.StringRewrites}, at class-load time.
 *
 * <p>Each rewrite is stack-neutral: the helper takes the call's operands (receiver first) and
 * returns the same type, so the call instruction is replaced in place ({@code invokevirtual} and
 * {@code invokestatic} are both 3 bytes). The helper's constant-pool entries are appended to the
 * pool and the rest of the class file is copied unchanged, so offsets, stack-map frames and {@code
 * max_stack} don't change. Nothing is parsed beyond the constant pool unless it refers to one of
 * the target methods. Any unexpected input leaves the class untouched.
 */
public final class StringCallSiteRewriter implements ClassFileTransformer {
  private static final String HELPER = "datadog/trace/bootstrap/jit/StringRewrites";

  /** A call to rewrite: target owner, name and descriptor, and the helper method to call. */
  static final class Rule {
    final String owner;
    final String name;
    final String descriptor;
    final boolean isStatic;
    final String helperName;
    final String helperDescriptor;

    Rule(String owner, String name, String descriptor, boolean isStatic, String helperName) {
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
      this.isStatic = isStatic;
      this.helperName = helperName;
      // instance methods take the receiver as the helper's first parameter
      this.helperDescriptor = isStatic ? descriptor : "(L" + owner + ";" + descriptor.substring(1);
    }
  }

  static final Rule[] RULES = {
    new Rule(
        "java/lang/String",
        "replaceAll",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        false,
        "replaceAll"),
    new Rule(
        "java/lang/String",
        "replaceFirst",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        false,
        "replaceFirst"),
    new Rule(
        "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false, "split"),
    new Rule(
        "java/lang/String", "split", "(Ljava/lang/String;I)[Ljava/lang/String;", false, "split"),
    new Rule("java/lang/String", "matches", "(Ljava/lang/String;)Z", false, "matches"),
    new Rule(
        "java/util/regex/Pattern",
        "matches",
        "(Ljava/lang/String;Ljava/lang/CharSequence;)Z",
        true,
        "patternMatches"),
  };

  /** Method names to look for in the constant pool before doing any more work. */
  private static final byte[][] NAME_NEEDLES = {
    ascii("replaceAll"), ascii("replaceFirst"), ascii("split"), ascii("matches")
  };

  private static final byte[] CODE = ascii("Code");

  // PROTOTYPE diagnostics: reported at exit with -Ddd.prototype.string-rewrites.report=true
  static final java.util.concurrent.atomic.AtomicLong scanned =
      new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicLong candidates =
      new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicLong rewritten =
      new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicLong rewrittenSites =
      new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicLong nanos =
      new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.atomic.AtomicLong>
      sitesByRule = new java.util.concurrent.ConcurrentHashMap<>();

  static final boolean REPORT = Boolean.getBoolean("dd.prototype.string-rewrites.report");
  static final java.util.Queue<String> rewrittenNames =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

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
                          + " byRule="
                          + sitesByRule
                          + " transformMs="
                          + nanos.get() / 1_000_000
                          + "\n[string-rewrites] classes="
                          + rewrittenNames)));
    }
  }

  static final boolean ANALYZE_ONLY =
      Boolean.getBoolean("dd.prototype.string-rewrites.analyze-only");

  private static final int INVOKEVIRTUAL = 0xB6;
  private static final int INVOKESTATIC = 0xB8;

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
    try {
      return doRewrite(b);
    } catch (IndexOutOfBoundsException e) {
      return null;
    }
  }

  private static byte[] doRewrite(byte[] b) throws IOException {
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
      switch (tag) {
        case 1:
          int length = readU2(b, offset + 1);
          if (!candidate) {
            for (byte[] needle : NAME_NEEDLES) {
              if (length == needle.length && regionEquals(b, offset + 3, needle)) {
                candidate = true;
                break;
              }
            }
          }
          offset += 3 + length;
          break;
        case 3:
        case 4:
        case 9:
        case 10:
        case 11:
        case 12:
        case 17:
        case 18:
          offset += 5;
          break;
        case 5:
        case 6:
          offset += 9;
          i++;
          break;
        case 7:
        case 8:
        case 16:
        case 19:
        case 20:
          offset += 3;
          break;
        case 15:
          offset += 4;
          break;
        default:
          return null; // unknown constant-pool tag
      }
    }
    if (!candidate) {
      return null; // most classes stop here, after one walk of the constant pool
    }
    candidates.incrementAndGet();
    int poolEnd = offset;

    // which Methodref entries are calls we rewrite
    int[] ruleForEntry = null;
    for (int i = 1; i < count; i++) {
      if (offsets[i] != 0 && b[offsets[i]] == 10) {
        int rule = matchRule(b, offsets, offsets[i]);
        if (rule >= 0) {
          if (null == ruleForEntry) {
            ruleForEntry = new int[count];
            Arrays.fill(ruleForEntry, -1);
          }
          ruleForEntry[i] = rule;
        }
      }
    }
    if (null == ruleForEntry) {
      return null;
    }

    // find the call instructions that use those entries
    List<int[]> sites = new ArrayList<>(); // {offset of opcode, rule}
    int codeIndex = findUtf8(b, offsets, count, CODE);
    if (codeIndex < 0) {
      return null;
    }
    int p = poolEnd + 6; // access, this, super
    int interfaces = readU2(b, p);
    p += 2 + 2 * interfaces;
    p = skipMembers(b, p, -1, null, null); // fields
    p = skipMembers(b, p, codeIndex, ruleForEntry, sites); // methods
    if (p < 0 || sites.isEmpty()) {
      return null;
    }

    // append one Methodref per rule used, then copy the class with the calls patched
    ByteArrayOutputStream appended = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(appended);
    int nextIndex = count;
    int helperName = nextIndex++;
    writeUtf8(out, HELPER);
    int helperClass = nextIndex++;
    out.writeByte(7);
    out.writeShort(helperName);
    int[] methodrefForRule = new int[RULES.length];
    for (int[] site : sites) {
      int rule = site[1];
      if (methodrefForRule[rule] == 0) {
        int name = nextIndex++;
        writeUtf8(out, RULES[rule].helperName);
        int descriptor = nextIndex++;
        writeUtf8(out, RULES[rule].helperDescriptor);
        int nameAndType = nextIndex++;
        out.writeByte(12);
        out.writeShort(name);
        out.writeShort(descriptor);
        methodrefForRule[rule] = nextIndex++;
        out.writeByte(10);
        out.writeShort(helperClass);
        out.writeShort(nameAndType);
      }
    }
    if (nextIndex > 0xFFFF) {
      return null; // constant pool would overflow
    }
    out.flush();
    byte[] extra = appended.toByteArray();

    if (ANALYZE_ONLY) {
      return null; // PROTOTYPE: isolates the cost of running rewritten sites from scanning
    }
    byte[] result = new byte[b.length + extra.length];
    System.arraycopy(b, 0, result, 0, poolEnd);
    writeU2(result, 8, nextIndex);
    System.arraycopy(extra, 0, result, poolEnd, extra.length);
    System.arraycopy(b, poolEnd, result, poolEnd + extra.length, b.length - poolEnd);
    rewritten.incrementAndGet();
    rewrittenSites.addAndGet(sites.size());
    for (int[] site : sites) {
      sitesByRule
          .computeIfAbsent(
              RULES[site[1]].name + RULES[site[1]].descriptor,
              k -> new java.util.concurrent.atomic.AtomicLong())
          .incrementAndGet();
    }
    for (int[] site : sites) {
      int at = site[0] + extra.length;
      result[at] = (byte) INVOKESTATIC;
      writeU2(result, at + 1, methodrefForRule[site[1]]);
    }
    return result;
  }

  /** Which rule a Methodref entry matches, or -1. */
  private static int matchRule(byte[] b, int[] offsets, int methodref) {
    int classOffset = offsets[readU2(b, methodref + 1)];
    int natOffset = offsets[readU2(b, methodref + 3)];
    if (b[classOffset] != 7 || b[natOffset] != 12) {
      return -1;
    }
    int owner = offsets[readU2(b, classOffset + 1)];
    int name = offsets[readU2(b, natOffset + 1)];
    int descriptor = offsets[readU2(b, natOffset + 3)];
    for (int r = 0; r < RULES.length; r++) {
      Rule rule = RULES[r];
      if (utf8Equals(b, name, rule.name)
          && utf8Equals(b, owner, rule.owner)
          && utf8Equals(b, descriptor, rule.descriptor)) {
        return r;
      }
    }
    return -1;
  }

  /**
   * Skips a fields or methods table; for methods, scans each Code attribute for calls to rewrite.
   * Returns the offset after the table, or -1 if the bytecode can't be walked.
   */
  private static int skipMembers(
      byte[] b, int p, int codeIndex, int[] ruleForEntry, List<int[]> sites) {
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
        if (null != sites && attributeName == codeIndex) {
          int codeLength = readInt(b, start + 4);
          if (!scanCode(b, start + 8, codeLength, ruleForEntry, sites)) {
            return -1;
          }
        }
        p = start + length;
      }
    }
    return p;
  }

  /** Walks one method's bytecode, recording the call sites to rewrite. */
  private static boolean scanCode(
      byte[] b, int codeStart, int codeLength, int[] ruleForEntry, List<int[]> sites) {
    int pc = 0;
    while (pc < codeLength) {
      int at = codeStart + pc;
      int opcode = b[at] & 0xFF;
      if (opcode == INVOKEVIRTUAL || opcode == INVOKESTATIC) {
        int index = readU2(b, at + 1);
        int rule = index < ruleForEntry.length ? ruleForEntry[index] : -1;
        if (rule >= 0 && RULES[rule].isStatic == (opcode == INVOKESTATIC)) {
          sites.add(new int[] {at, rule});
        }
      }
      int length = instructionLength(b, codeStart, pc, opcode);
      if (length <= 0) {
        return false;
      }
      pc += length;
    }
    return pc == codeLength;
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

  private static void writeUtf8(DataOutputStream out, String value) throws IOException {
    out.writeByte(1);
    out.writeUTF(value); // modified UTF-8 with a u2 length, as in class files
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static int readU2(byte[] b, int offset) {
    return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
  }

  private static int readInt(byte[] b, int offset) {
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
