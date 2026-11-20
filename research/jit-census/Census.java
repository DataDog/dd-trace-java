import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * Static census of String/Pattern regex-consuming call sites with constant arguments, plus a
 * prototype constant-pool prescan (and a rough, non-JMH timing of it).
 *
 * <p>Usage: java -Xmx6g -cp asm.jar Census.java <gradle files-2.1 root> <out dir>
 */
public class Census {

  // ---------------------------------------------------------------- prescan feature bits
  static final int F_SPLIT = 1, F_MATCHES = 2, F_REPLACEALL = 4, F_REPLACEFIRST = 8,
      F_REPLACECS = 16, F_FORMAT = 32, F_PCOMPILE = 64, F_PMATCHES = 128, F_PQUOTE = 256;
  static final int F_ANY_TARGET = 0x1FF;
  static final int F_S0CONST = 1 << 16; // String constant whose raw bytes look S0-foldable
  static final String[] FEATURE_NAMES = {
    "String.split", "String.matches", "String.replaceAll", "String.replaceFirst",
    "String.replace(CS,CS)", "String.format/formatted", "Pattern.compile", "Pattern.matches",
    "Pattern.quote"
  };

  static final byte[] B_STRING = utf8("java/lang/String"), B_PATTERN = utf8("java/util/regex/Pattern");
  static final byte[] B_SPLIT = utf8("split"), B_MATCHES = utf8("matches"),
      B_REPLACEALL = utf8("replaceAll"), B_REPLACEFIRST = utf8("replaceFirst"),
      B_REPLACE = utf8("replace"), B_FORMAT = utf8("format"), B_FORMATTED = utf8("formatted"),
      B_COMPILE = utf8("compile"), B_QUOTE = utf8("quote");
  static final byte[] B_REPLACE_CS_DESC =
      utf8("(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;");

  static byte[] utf8(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  // reusable scratch (single-threaded)
  static final int[] OFF = new int[65536];
  static final byte[] TAG = new byte[65536];

  static int u2(byte[] b, int p) {
    return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
  }

  static boolean utf8Eq(byte[] b, int utf8Idx, byte[] want) {
    int p = OFF[utf8Idx];
    int len = u2(b, p);
    if (len != want.length) return false;
    p += 2;
    for (int i = 0; i < len; i++) if (b[p + i] != want[i]) return false;
    return true;
  }

  static int safePrescan(byte[] b) {
    try { return prescan(b); } catch (RuntimeException e) { return -1; }
  }

  /** One linear pass over the constant pool; no method parsing. Returns -1 if unparseable. */
  static int prescan(byte[] b) {
    if (b.length < 10 || u2(b, 0) != 0xCAFE || u2(b, 2) != 0xBABE) return -1;
    int n = u2(b, 8);
    int p = 10;
    for (int i = 1; i < n; i++) {
      int t = b[p] & 0xff;
      TAG[i] = (byte) t;
      OFF[i] = p + 1;
      switch (t) {
        case 1: p += 3 + u2(b, p + 1); break;
        case 3: case 4: p += 5; break;
        case 5: case 6: p += 9; TAG[++i] = 0; break;
        case 7: case 8: case 16: case 19: case 20: p += 3; break;
        case 9: case 10: case 11: case 12: case 17: case 18: p += 5; break;
        case 15: p += 4; break;
        default: return -1;
      }
    }
    int flags = 0;
    for (int i = 1; i < n; i++) {
      if (TAG[i] != 10) continue; // Methodref (String & Pattern are classes)
      int clsUtf8 = u2(b, OFF[u2(b, OFF[i])]);
      int nat = u2(b, OFF[i] + 2);
      int name = u2(b, OFF[nat]);
      if (utf8Eq(b, clsUtf8, B_STRING)) {
        if (utf8Eq(b, name, B_SPLIT)) flags |= F_SPLIT;
        else if (utf8Eq(b, name, B_MATCHES)) flags |= F_MATCHES;
        else if (utf8Eq(b, name, B_REPLACEALL)) flags |= F_REPLACEALL;
        else if (utf8Eq(b, name, B_REPLACEFIRST)) flags |= F_REPLACEFIRST;
        else if (utf8Eq(b, name, B_FORMAT) || utf8Eq(b, name, B_FORMATTED)) flags |= F_FORMAT;
        else if (utf8Eq(b, name, B_REPLACE) && utf8Eq(b, u2(b, OFF[nat] + 2), B_REPLACE_CS_DESC))
          flags |= F_REPLACECS;
      } else if (utf8Eq(b, clsUtf8, B_PATTERN)) {
        if (utf8Eq(b, name, B_COMPILE)) flags |= F_PCOMPILE;
        else if (utf8Eq(b, name, B_MATCHES)) flags |= F_PMATCHES;
        else if (utf8Eq(b, name, B_QUOTE)) flags |= F_PQUOTE;
      }
    }
    if ((flags & F_SPLIT) != 0) {
      for (int i = 1; i < n; i++) {
        if (TAG[i] != 8) continue;
        int q = OFF[u2(b, OFF[i])];
        int len = u2(b, q);
        q += 2;
        if (s0Bytes(b, q, len)) {
          flags |= F_S0CONST;
          break;
        }
      }
    }
    return flags;
  }

  /** Raw-byte S0 shape test: "\\t"-style escapes, "[c]", "\\Q..", "\\x..", "\\u..". */
  static boolean s0Bytes(byte[] b, int q, int len) {
    if (len == 2 && b[q] == '\\') return "tnrfae".indexOf(b[q + 1]) >= 0;
    if (len == 3 && b[q] == '[' && b[q + 2] == ']') return true;
    if (len == 4 && b[q] == '[' && b[q + 1] == '\\' && b[q + 3] == ']') return true;
    if (len >= 4 && len <= 8 && b[q] == '\\') return b[q + 1] == 'Q' || b[q + 1] == 'x' || b[q + 1] == 'u';
    return false;
  }

  // ---------------------------------------------------------------- regex shape classifier
  enum Shape {
    SPLIT_FASTPATH, S0_FOLD, LITERAL, CASEI_LITERAL, CHARCLASS, CHAR_RUN, WS_SEP, ANCHORED_LITERAL, LINES, DOTSTAR,
    ALT_LITERALS, OTHER, INVALID
  }

  static final String META = ".$|()[{^?*+";

  /** Unescape a regex that denotes a pure literal; null if it isn't one. */
  static String unescapeLiteral(String r) {
    StringBuilder sb = new StringBuilder();
    int n = r.length();
    for (int i = 0; i < n; ) {
      char c = r.charAt(i);
      if (c == '\\') {
        if (i + 1 >= n) return null;
        char d = r.charAt(i + 1);
        if (d == 'Q') {
          int end = r.indexOf("\\E", i + 2);
          if (end < 0) { sb.append(r, i + 2, n); i = n; }
          else { sb.append(r, i + 2, end); i = end + 2; }
          continue;
        }
        if (Character.isLetterOrDigit(d) && d < 128) {
          switch (d) {
            case 't': sb.append('\t'); i += 2; continue;
            case 'n': sb.append('\n'); i += 2; continue;
            case 'r': sb.append('\r'); i += 2; continue;
            case 'f': sb.append('\f'); i += 2; continue;
            case 'a': sb.append('\u0007'); i += 2; continue;
            case 'e': sb.append('\u001B'); i += 2; continue;
            case 'x':
              if (i + 4 <= n && isHex(r, i + 2, i + 4)) {
                sb.append((char) Integer.parseInt(r.substring(i + 2, i + 4), 16)); i += 4; continue;
              }
              return null;
            case 'u':
              if (i + 6 <= n && isHex(r, i + 2, i + 6)) {
                sb.append((char) Integer.parseInt(r.substring(i + 2, i + 6), 16)); i += 6; continue;
              }
              return null;
            default: return null;
          }
        }
        sb.append(d);
        i += 2;
      } else if (META.indexOf(c) >= 0) {
        return null;
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  static boolean isHex(String s, int from, int to) {
    for (int i = from; i < to; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
    return true;
  }

  static boolean jdkSplitFastpath(String r) {
    char ch;
    if (r.length() == 1) {
      ch = r.charAt(0);
      if (".$|()[{^?*+\\".indexOf(ch) != -1) return false;
    } else if (r.length() == 2 && r.charAt(0) == '\\') {
      ch = r.charAt(1);
      if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) return false;
    } else {
      return false;
    }
    return ch < Character.MIN_HIGH_SURROGATE || ch > Character.MAX_LOW_SURROGATE;
  }

  static final Pattern CHARCLASS_RE =
      Pattern.compile("(\\[\\^?(?:[^\\[\\]&\\\\]|\\\\[^pPQE])+\\]|\\\\[sSdDwW])[+*]?");
  static final Set<String> LINES_SET = Set.of(
      "\\r?\\n", "\\R", "\\n|\\r\\n", "\\r\\n|\\n", "\\r\\n|\\r|\\n", "\\n|\\r", "\\r\\n",
      "(\\r\\n|\\n)", "(\\r\\n|\\r|\\n)", "\\r?\\n|\\r", "[\\r\\n]+", "[\\n\\r]+", "\\n+", "\\r?\\n\\r?\\n");
  static final Pattern ALT_SPLIT = Pattern.compile("(?<!\\\\)\\|");

  static Shape classify(String r, boolean forSplit, boolean wholeMatch) {
    try {
      Pattern.compile(r);
    } catch (PatternSyntaxException e) {
      return Shape.INVALID;
    }
    if (forSplit && jdkSplitFastpath(r)) return Shape.SPLIT_FASTPATH;
    if (LINES_SET.contains(r)) return Shape.LINES;
    String core = r;
    if (wholeMatch) {
      if (core.startsWith("^")) core = core.substring(1);
      if (core.endsWith("$") && !core.endsWith("\\$")) core = core.substring(0, core.length() - 1);
    }
    String lit = unescapeLiteral(core);
    if (lit != null) {
      if (forSplit && lit.length() == 1
          && !(lit.charAt(0) >= Character.MIN_HIGH_SURROGATE && lit.charAt(0) <= Character.MAX_LOW_SURROGATE))
        return Shape.S0_FOLD;
      if (forSplit && core.length() == 3 && core.charAt(0) == '[') return Shape.S0_FOLD;
      return Shape.LITERAL;
    }
    // single-char class "[c]" / "[\\c]" folds to a literal char
    if (core.length() >= 3 && core.charAt(0) == '[' && core.charAt(core.length() - 1) == ']'
        && core.charAt(1) != '^') {
      String inner = core.substring(1, core.length() - 1);
      String il = inner.length() == 1 ? inner : (inner.length() == 2 && inner.charAt(0) == '\\' ? unescapeLiteral(inner) : null);
      if (il != null && il.length() == 1) return forSplit ? Shape.S0_FOLD : Shape.LITERAL;
    }
    if (core.startsWith("(?i)") && unescapeLiteral(core.substring(4)) != null) return Shape.CASEI_LITERAL;
    if (CHARCLASS_RE.matcher(core).matches()) return Shape.CHARCLASS;
    // literal char repeated: " +", "/+", ",+"
    if (core.length() >= 2 && (core.endsWith("+") || core.endsWith("*")) && !core.endsWith("\\+") && !core.endsWith("\\*")) {
      String one = unescapeLiteral(core.substring(0, core.length() - 1));
      if (one != null && one.length() == 1) return Shape.CHAR_RUN;
    }
    // literal separator with optional whitespace padding: "\\s*,\\s*", " *, *", ",\\s*"
    {
      String m = core;
      boolean padded = false;
      for (String pad : new String[] {"\\s*", "\\s+", " *", " +"}) {
        if (m.startsWith(pad)) { m = m.substring(pad.length()); padded = true; break; }
      }
      for (String pad : new String[] {"\\s*", "\\s+", " *", " +"}) {
        if (m.endsWith(pad) && !m.endsWith("\\" + pad)) { m = m.substring(0, m.length() - pad.length()); padded = true; break; }
      }
      String mid = padded ? unescapeLiteral(m) : null;
      if (mid != null && !mid.isEmpty()) return Shape.WS_SEP;
    }
    // anchored literal (find-mode): "^/", ",$"  -- trailing $ also matches before a final line terminator
    if (!wholeMatch) {
      if (core.startsWith("^")) {
        String l = unescapeLiteral(core.substring(1));
        if (l != null && !l.isEmpty()) return Shape.ANCHORED_LITERAL;
      }
      if (core.endsWith("$") && !core.endsWith("\\$")) {
        String l = unescapeLiteral(core.substring(0, core.length() - 1));
        if (l != null && !l.isEmpty()) return Shape.ANCHORED_LITERAL;
      }
    }
    if (wholeMatch) {
      String c2 = core;
      boolean pre = c2.startsWith(".*"), post = c2.endsWith(".*");
      if (pre) c2 = c2.substring(2);
      if (post && c2.length() >= 2) c2 = c2.substring(0, c2.length() - 2);
      if ((pre || post) && !c2.isEmpty() && unescapeLiteral(c2) != null) return Shape.DOTSTAR;
    }
    String alt = core;
    if (alt.startsWith("(?:") && alt.endsWith(")")) alt = alt.substring(3, alt.length() - 1);
    else if (alt.startsWith("(") && alt.endsWith(")")) alt = alt.substring(1, alt.length() - 1);
    if (alt.indexOf('|') >= 0) {
      boolean all = true;
      for (String part : ALT_SPLIT.split(alt, -1)) {
        String pl = unescapeLiteral(part);
        if (pl == null || pl.isEmpty()) { all = false; break; }
      }
      if (all) return Shape.ALT_LITERALS;
    }
    return Shape.OTHER;
  }

  static final Pattern FMT_SPEC =
      Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

  static String classifyFormat(String f) {
    Matcher m = FMT_SPEC.matcher(f);
    boolean any = false, onlyS = true, onlySD = true;
    while (m.find()) {
      String conv = m.group(6);
      if (conv.equals("%") || conv.equals("n")) continue;
      any = true;
      boolean plain = m.group(1) == null && (m.group(2) == null || m.group(2).isEmpty())
          && m.group(3) == null && m.group(4) == null && m.group(5) == null;
      if (!(plain && conv.equals("s"))) onlyS = false;
      if (!(plain && (conv.equals("s") || conv.equals("d")))) onlySD = false;
    }
    if (!any) return "no-specifiers";
    if (onlyS) return "plain-%s-only";
    if (onlySD) return "plain-%s/%d-only";
    return "other";
  }

  // ---------------------------------------------------------------- stats
  static final class Stat {
    long sites;
    final Set<String> artifacts = new HashSet<>();
    final Map<String, Integer> constants = new HashMap<>();
  }

  static final Map<String, Stat> STATS = new TreeMap<>();
  static final Map<String, Long> TOTAL_CALLS = new TreeMap<>(); // all target calls (const or not)
  static final Map<String, long[]> CLASS_VERSIONS = new TreeMap<>(); // per site-kind version buckets
  static final List<String> S0_EXAMPLES = new ArrayList<>();
  static String curArtifact, curClass, curMethod;
  static int curVersion;
  static boolean curClassHadSite, curClassHadS0Site;

  static final Map<String, Map<String, Integer>> BY_ART = new TreeMap<>();
  static final Map<String, Integer> CLASSES_BY_ART = new TreeMap<>();

  static void record(String key, String constant) {
    if (!key.contains("used-directly"))
      BY_ART.computeIfAbsent(curArtifact, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
    Stat s = STATS.computeIfAbsent(key, k -> new Stat());
    s.sites++;
    s.artifacts.add(curArtifact);
    if (constant != null) s.constants.merge(constant, 1, Integer::sum);
    curClassHadSite = true;
    long[] v = CLASS_VERSIONS.computeIfAbsent(key.substring(0, key.indexOf('|')), k -> new long[3]);
    v[curVersion >= 55 ? 2 : curVersion >= 51 ? 1 : 0]++;
  }

  static void total(String method) {
    TOTAL_CALLS.merge(method, 1L, Long::sum);
  }

  // ---------------------------------------------------------------- body scan
  record Insn(int op, Object a, Object b, Object c) {}

  static boolean isIntPush(Insn i) {
    return i != null && ((i.op >= Opcodes.ICONST_M1 && i.op <= Opcodes.ICONST_5)
        || i.op == Opcodes.BIPUSH || i.op == Opcodes.SIPUSH
        || (i.op == Opcodes.LDC && i.a instanceof Integer));
  }

  static boolean isSimplePush(Insn i) {
    return i != null && (i.op == Opcodes.LDC || i.op == Opcodes.ALOAD || i.op == Opcodes.GETSTATIC
        || i.op == Opcodes.ACONST_NULL);
  }

  static String ldcString(Insn i) {
    return i != null && i.op == Opcodes.LDC && i.a instanceof String s ? s : null;
  }

  static final class Scanner extends MethodVisitor {
    final Insn[] hist = new Insn[8];
    int h = 0;
    final Deque<String> pendingFormats = new ArrayDeque<>();
    String pendingCompileKey;

    Scanner() { super(Opcodes.ASM9); }

    Insn back(int k) { // k = 1 is the most recent
      return h - k >= 0 ? hist[(h - k) % hist.length] : null;
    }

    void push(Insn i) {
      if (pendingCompileKey != null) {
        String where = i.op == Opcodes.PUTSTATIC ? "stored-static" : i.op == Opcodes.PUTFIELD ? "stored-field" : "used-directly";
        STATS.computeIfAbsent(pendingCompileKey + "|" + where, k -> new Stat()).sites++;
        pendingCompileKey = null;
      }
      // format pattern: LDC fmt ; int push ; ANEWARRAY java/lang/Object
      if (i.op == Opcodes.ANEWARRAY && "java/lang/Object".equals(i.a) && isIntPush(back(1))) {
        String f = ldcString(back(2));
        if (f != null) pendingFormats.push(f);
      }
      hist[h % hist.length] = i;
      h++;
    }

    /** Regex argument at stack depth 1 (one simple arg pushed after it). */
    String constAtDepth1() {
      Insn a = back(1);
      if (isSimplePush(a)) return ldcString(back(2));
      if (a != null && a.op == Opcodes.GETFIELD && back(2) != null && back(2).op == Opcodes.ALOAD)
        return ldcString(back(3));
      return null;
    }

    @Override public void visitInsn(int op) { push(new Insn(op, null, null, null)); }
    @Override public void visitIntInsn(int op, int operand) { push(new Insn(op, operand, null, null)); }
    @Override public void visitVarInsn(int op, int v) { push(new Insn(op, v, null, null)); }
    @Override public void visitTypeInsn(int op, String t) { push(new Insn(op, t, null, null)); }
    @Override public void visitFieldInsn(int op, String o, String n, String d) { push(new Insn(op, o, n, d)); }
    @Override public void visitLdcInsn(Object v) { push(new Insn(Opcodes.LDC, v, null, null)); }
    @Override public void visitJumpInsn(int op, Label l) { push(new Insn(op, null, null, null)); }
    @Override public void visitIincInsn(int v, int inc) { push(new Insn(Opcodes.IINC, null, null, null)); }
    @Override public void visitTableSwitchInsn(int a, int b, Label d, Label... l) { push(new Insn(Opcodes.TABLESWITCH, null, null, null)); }
    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { push(new Insn(Opcodes.LOOKUPSWITCH, null, null, null)); }
    @Override public void visitMultiANewArrayInsn(String d, int n) { push(new Insn(Opcodes.MULTIANEWARRAY, null, null, null)); }
    @Override public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... a) { push(new Insn(Opcodes.INVOKEDYNAMIC, null, null, null)); }

    @Override
    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
      if (owner.equals("java/lang/String")) onString(name, desc);
      else if (owner.equals("java/util/regex/Pattern")) onPattern(name, desc);
      push(new Insn(op, owner, name, desc));
    }

    void site(String method, String regex, boolean forSplit, boolean wholeMatch) {
      Shape s = classify(regex, forSplit, wholeMatch);
      record(method + "|" + s, regex);
      if (s == Shape.S0_FOLD) {
        curClassHadS0Site = true;
        if (S0_EXAMPLES.size() < 400)
          S0_EXAMPLES.add(curArtifact + "  " + curClass + "." + curMethod + "  " + method + "(\"" + escapeJava(regex) + "\")");
      }
    }

    void onString(String name, String desc) {
      switch (name + desc) {
        case "split(Ljava/lang/String;)[Ljava/lang/String;" -> {
          total("String.split");
          String r = ldcString(back(1));
          Insn b1 = back(1);
          if (r != null) site("String.split", r, true, false);
          else if (b1 != null && b1.op == Opcodes.INVOKESTATIC && "java/util/regex/Pattern".equals(b1.a)
              && "quote".equals(b1.b) && ldcString(back(2)) != null)
            record("String.split|QUOTED_LITERAL(S3)", ldcString(back(2)));
        }
        case "split(Ljava/lang/String;I)[Ljava/lang/String;" -> {
          total("String.split");
          if (isIntPush(back(1))) {
            String r = ldcString(back(2));
            if (r != null) site("String.split", r, true, false);
          }
        }
        case "matches(Ljava/lang/String;)Z" -> {
          total("String.matches");
          String r = ldcString(back(1));
          if (r != null) site("String.matches", r, false, true);
        }
        case "replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;" -> {
          total("String.replaceAll");
          String r = constAtDepth1();
          if (r != null) site("String.replaceAll", r, false, false);
        }
        case "replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;" -> {
          total("String.replaceFirst");
          String r = constAtDepth1();
          if (r != null) site("String.replaceFirst", r, false, false);
        }
        case "replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;" -> {
          total("String.replace(CS,CS)");
          String r = constAtDepth1();
          if (r != null) record("String.replace(CS,CS)|CONST_TARGET", r);
        }
        default -> {
          if ((name.equals("format") || name.equals("formatted")) && desc.endsWith("[Ljava/lang/Object;)Ljava/lang/String;")) {
            total("String.format/formatted");
            String f = pendingFormats.poll();
            if (f != null) record("String.format/formatted|" + classifyFormat(f), f);
          }
        }
      }
    }

    void onPattern(String name, String desc) {
      switch (name + desc) {
        case "compile(Ljava/lang/String;)Ljava/util/regex/Pattern;" -> {
          total("Pattern.compile");
          String r = ldcString(back(1));
          if (r != null) compileSite(r);
        }
        case "compile(Ljava/lang/String;I)Ljava/util/regex/Pattern;" -> {
          total("Pattern.compile");
          if (isIntPush(back(1))) {
            String r = ldcString(back(2));
            if (r != null) compileSite(r);
          }
        }
        case "matches(Ljava/lang/String;Ljava/lang/CharSequence;)Z" -> {
          total("Pattern.matches");
          String r = constAtDepth1();
          if (r != null) site("Pattern.matches", r, false, true);
        }
        case "quote(Ljava/lang/String;)Ljava/lang/String;" -> {
          total("Pattern.quote");
          String r = ldcString(back(1));
          if (r != null) record("Pattern.quote|CONST", r);
        }
        default -> {}
      }
    }

    void compileSite(String r) {
      String where = curMethod.equals("<clinit>") ? "in-clinit" : curMethod.equals("<init>") ? "in-ctor" : "in-method";
      record("Pattern.compile[" + where + "]|" + classify(r, false, false), r);
      pendingCompileKey = "Pattern.compile[" + where + "]";
    }
  }

  static String escapeJava(String s) {
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\t' -> sb.append("\\t");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        default -> { if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
      }
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- corpus
  static int cmpVersion(String a, String b) {
    String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      String p = i < x.length ? x[i] : "", q = i < y.length ? y[i] : "";
      boolean pn = !p.isEmpty() && p.chars().allMatch(Character::isDigit);
      boolean qn = !q.isEmpty() && q.chars().allMatch(Character::isDigit);
      int c = pn && qn ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
      if (c != 0) return c;
    }
    return 0;
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    Path out = Paths.get(args[1]);
    Files.createDirectories(out);

    // artifact name -> roots (jar files, or directories inside a jrt filesystem)
    Map<String, List<Path>> sources = new TreeMap<>();
    if (args[0].startsWith("jdk:")) {
      Path home = Paths.get(args[0].substring(4));
      if (Files.exists(home.resolve("lib/modules"))) {
        FileSystem jrt = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", home.toString()));
        try (DirectoryStream<Path> mods = Files.newDirectoryStream(jrt.getPath("/modules"))) {
          for (Path m : mods) { String mn = m.getFileName().toString().replace("/", ""); String only = System.getProperty("modules"); if (only == null || Arrays.asList(only.split(",")).contains(mn)) sources.put(mn, List.of(m)); }
        }
      } else {
        try (Stream<Path> s = Files.walk(home.resolve("jre/lib"), 2)) {
          s.filter(Census::isBinaryJar).forEach(j -> sources.put(j.getFileName().toString(), List.of(j)));
        }
      }
    } else {
      for (Map.Entry<String, Path> e : latestArtifacts(root).entrySet()) {
        try (Stream<Path> s = Files.walk(e.getValue(), 2)) {
          sources.put(e.getKey(), s.filter(Census::isBinaryJar).toList());
        }
      }
    }
    runCensus(sources, out);
  }

  static Map<String, Path> latestArtifacts(Path root) throws IOException {
    // group/artifact/version/hash/file.jar ; keep max version per group:artifact
    Map<String, Path> latest = new TreeMap<>();
    Map<String, String> latestVer = new HashMap<>();
    try (DirectoryStream<Path> groups = Files.newDirectoryStream(root)) {
      for (Path g : groups) {
        if (g.getFileName().toString().startsWith("com.datadoghq")) continue;
        if (!Files.isDirectory(g)) continue;
        try (DirectoryStream<Path> arts = Files.newDirectoryStream(g)) {
          for (Path a : arts) {
            if (!Files.isDirectory(a)) continue;
            try (DirectoryStream<Path> vers = Files.newDirectoryStream(a)) {
              for (Path v : vers) {
                String key = g.getFileName() + ":" + a.getFileName();
                String ver = v.getFileName().toString();
                if (!latestVer.containsKey(key) || cmpVersion(ver, latestVer.get(key)) > 0) {
                  try (Stream<Path> s = Files.walk(v, 2)) {
                    if (s.anyMatch(Census::isBinaryJar)) {
                      latestVer.put(key, ver);
                      latest.put(key, v);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    Map<String, Path> named = new TreeMap<>();
    latest.forEach((k, v) -> named.put(k + ":" + latestVer.get(k), v));
    return named;
  }

  interface ClassSink {
    void accept(String entryName, byte[] bytes);
  }

  static void forEachClass(List<Path> roots, ClassSink sink) {
    for (Path r : roots) {
      try {
        if (r.getFileSystem() == FileSystems.getDefault() && r.toString().endsWith(".jar")) {
          try (ZipFile zf = new ZipFile(r.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
              ZipEntry ze = en.nextElement();
              String n = ze.getName();
              if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) continue;
              try (InputStream in = zf.getInputStream(ze)) { sink.accept(n, in.readAllBytes()); }
            }
          }
        } else {
          List<Path> files;
          try (Stream<Path> s = Files.walk(r)) {
            files = s.filter(p -> p.toString().endsWith(".class") && !p.toString().endsWith("module-info.class")).toList();
          }
          for (Path p : files) sink.accept(r.relativize(p).toString(), Files.readAllBytes(p));
        }
      } catch (IOException ex) {
        System.err.println("skip " + r + ": " + ex);
      }
    }
  }

  static long classes = 0, unparseable = 0, asmFailed = 0, bytesTotal = 0;
  static long anyHit = 0, s0Prescan = 0, s0PrescanWithSite = 0, classesWithSite = 0, siteMissedByPrescan = 0;
  static long classesWithS0Site = 0;
  static final long[] featureHits = new long[FEATURE_NAMES.length];
  static final List<byte[]> timingSample = new ArrayList<>();
  static long sampleBytes = 0;
  static final long SAMPLE_CAP = 1_200_000_000L;

  static void scanOne(String n, byte[] b) {
    classes++;
    CLASSES_BY_ART.merge(curArtifact, 1, Integer::sum);
    bytesTotal += b.length;
    if (sampleBytes < SAMPLE_CAP) { timingSample.add(b); sampleBytes += b.length; }
    int f = safePrescan(b);
    if (f < 0) { unparseable++; return; }
    for (int i = 0; i < FEATURE_NAMES.length; i++) if ((f & (1 << i)) != 0) featureHits[i]++;
    boolean hit = (f & F_ANY_TARGET) != 0;
    if (hit) anyHit++;
    boolean s0p = (f & F_S0CONST) != 0;
    if (s0p) s0Prescan++;
    // body-scan EVERY class (not just hits) so we can verify the prescan never misses
    curClass = n.substring(0, n.length() - 6).replace('/', '.');
    curVersion = u2(b, 6);
    curClassHadSite = false;
    curClassHadS0Site = false;
    try {
      new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
          curMethod = name;
          return new Scanner();
        }
      }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (Throwable t) {
      asmFailed++;
    }
    if (curClassHadSite) { classesWithSite++; if (!hit) siteMissedByPrescan++; }
    if (curClassHadS0Site) { classesWithS0Site++; if (s0p) s0PrescanWithSite++; }
  }

  static void runCensus(Map<String, List<Path>> sources, Path out) throws IOException {
    for (Map.Entry<String, List<Path>> e : sources.entrySet()) {
      curArtifact = e.getKey();
      forEachClass(e.getValue(), Census::scanOne);
    }
    // ---- rough prescan timing (NOT JMH: warm a few rounds, then measure)
    long sink = 0;
    for (int r = 0; r < 3; r++) for (byte[] b : timingSample) sink += safePrescan(b);
    long[] rounds = new long[5];
    for (int r = 0; r < rounds.length; r++) {
      long t0 = System.nanoTime();
      for (byte[] b : timingSample) sink += safePrescan(b);
      rounds[r] = System.nanoTime() - t0;
    }
    Arrays.sort(rounds);

    try (PrintStream ps = new PrintStream(Files.newOutputStream(out.resolve("report.txt")))) {
      for (PrintStream p : List.of(System.out, ps)) {
        p.printf("artifacts: %d%n", sources.size());
        p.printf("classes: %,d  (%,d MB)  unparseable-cp: %d  asm-failed: %d%n", classes, bytesTotal >> 20, unparseable, asmFailed);
        p.println();
        p.println("== Prescan hit rates (fraction of all classes)");
        p.printf("  any target method ref : %,9d  %6.2f%%%n", anyHit, 100.0 * anyHit / classes);
        for (int i = 0; i < FEATURE_NAMES.length; i++)
          p.printf("  %-22s: %,9d  %6.2f%%%n", FEATURE_NAMES[i], featureHits[i], 100.0 * featureHits[i] / classes);
        p.printf("  split + S0-shaped const: %,9d  %6.3f%%%n", s0Prescan, 100.0 * s0Prescan / classes);
        p.println();
        p.println("== Prescan soundness / precision");
        p.printf("  classes with >=1 const site: %,d ; missed by prescan: %d (must be 0)%n", classesWithSite, siteMissedByPrescan);
        p.printf("  precision of any-target filter: %.1f%% of hits have a const site%n", 100.0 * classesWithSite / Math.max(1, anyHit));
        p.printf("  S0 filter: %,d prescan hits, %,d classes with real S0 site, %,d of those caught%n", s0Prescan, classesWithS0Site, s0PrescanWithSite);
        p.println();
        p.printf("== Rough prescan cost (NOT JMH; sample %,d classes, %,d MB): median %.0f ns/class, %.1f ns/KB%n",
            timingSample.size(), sampleBytes >> 20, (double) rounds[2] / timingSample.size(), (double) rounds[2] / (sampleBytes / 1024.0));
        p.println();
        p.println("== All calls to target methods (const + non-const)");
        TOTAL_CALLS.forEach((k, v) -> p.printf("  %-26s %,9d%n", k, v));
        p.println();
        p.println("== Constant-argument sites: method | shape   sites   #artifacts   top constants");
        STATS.forEach((k, s) -> {
          String top = s.constants.entrySet().stream()
              .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(6)
              .map(x -> "\"" + escapeJava(x.getKey()) + "\"x" + x.getValue()).collect(Collectors.joining("  "));
          p.printf("  %-48s %,7d  %5d   %s%n", k, s.sites, s.artifacts.size(), top);
        });
        p.println();
        p.println("== Class-file versions of const sites  [<51 | 51-54 (indy ok) | >=55 (condy ok)]");
        CLASS_VERSIONS.forEach((k, v) -> p.printf("  %-34s %,7d | %,7d | %,7d%n", k, v[0], v[1], v[2]));
      }
    }
    List<String> byArt = new ArrayList<>();
    List<String> csv = new ArrayList<>();
    csv.add("artifact,classes,site_kind,shape,count");
    CLASSES_BY_ART.forEach((a, c) -> {
      Map<String, Integer> m = BY_ART.getOrDefault(a, Map.of());
      if (m.isEmpty()) csv.add(a + "," + c + ",,,0");
      m.forEach((k, v) -> { int i = k.indexOf('|'); csv.add(a + "," + c + ",\"" + k.substring(0, i) + "\",\"" + k.substring(i + 1) + "\"," + v); });
    });
    Files.write(out.resolve("sites.csv"), csv);
    BY_ART.forEach((a, m) -> {
      byArt.add(a + "  (" + m.entrySet().stream().filter(x -> !x.getKey().endsWith("|SPLIT_FASTPATH")).mapToInt(Map.Entry::getValue).sum() + " sites excl. split fast path)");
      m.forEach((k, v) -> { if (!k.endsWith("|SPLIT_FASTPATH")) byArt.add(String.format("    %-48s %5d", k, v)); });
    });
    Files.write(out.resolve("by-artifact.txt"), byArt);
    Files.write(out.resolve("s0-examples.txt"), S0_EXAMPLES);
    if (sink == 42) System.out.println();
  }

  static boolean isBinaryJar(Path p) {
    String n = p.getFileName().toString();
    return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar") && !n.endsWith("-test-sources.jar");
  }
}
