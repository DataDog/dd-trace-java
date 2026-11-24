import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * How the constant-pool walk scales with the number of rules.
 *
 * <p>Rules are real JDK methods (owner under java/) that the corpus actually calls, so maybe-rates
 * are realistic. The 5 original rules come first; the rest are sampled with a fixed seed.
 *
 * <p>Two walks over the constant pool, both allocation-free per class:
 *
 * <ul>
 *   <li>linear: each Utf8 entry is compared against every needle of matching length (the original
 *       prototype, cost grows with the rule count);
 *   <li>indexed: a two-level index (byte length, then first byte) gives the candidate needles, so
 *       nearly every entry costs one or two array loads whatever the rule count.
 * </ul>
 *
 * <p>Usage: java -cp asm.jar:. CpScanScale jar-paths.txt
 */
public class CpScanScale {
  static final String[][] BASE = {
    {"java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;"},
    {"java/lang/String", "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"},
    {"java/lang/String", "matches", "(Ljava/lang/String;)Z"},
    {"java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"},
    {"java/util/regex/Pattern", "compile", "(Ljava/lang/String;)Ljava/util/regex/Pattern;"},
  };

  /** A compiled rule set: distinct needles (names and descriptors) and rule -> needle ids. */
  static final class RuleSet {
    final byte[][] needles;
    final int[] ruleName, ruleDesc, ruleOwner;
    static boolean OWNER = Boolean.getBoolean("owner");
    final int maxLen;
    /** byLenFirst[len][firstByte] -> candidate needle ids, or null. */
    final int[][][] byLenFirst;
    /** byLen[len] -> candidate needle ids, or null (for the linear walk). */
    final int[][] byLen;
    final long[] found; // scratch bitset over needles, reused across calls (single-threaded)
    /** 64K-bit (8 KB) signature filter over (length, first, middle, last byte). */
    final long[] sig = new long[1024];
    static boolean SIG = false;

    static int sigOf(int len, int first, int mid, int last) {
      int h = len * 0x9E3779B1 ^ first * 0x85EBCA77 ^ mid * 0xC2B2AE3D ^ last * 0x27D4EB2F;
      return (h ^ (h >>> 16)) & 0xFFFF;
    }
    /** nameToRules[needle] -> rules whose name is that needle, or null. */
    final int[][] nameToRules;
    final int[] foundNames; // scratch list of found name needles
    int foundNameCount;
    final int[] utf8Pos; // scratch for the two-pass walk: offset of each Utf8 entry's bytes
    final int[] utf8Len;

    RuleSet(List<String[]> rules) {
      Map<String, Integer> ids = new HashMap<>();
      List<byte[]> ns = new ArrayList<>();
      ruleName = new int[rules.size()];
      ruleDesc = new int[rules.size()];
      ruleOwner = new int[rules.size()];
      for (int r = 0; r < rules.size(); r++) {
        ruleName[r] = id(ids, ns, rules.get(r)[1]);
        ruleDesc[r] = id(ids, ns, rules.get(r)[2]);
        ruleOwner[r] = OWNER ? id(ids, ns, rules.get(r)[0]) : ruleDesc[r];
      }
      needles = ns.toArray(new byte[0][]);
      int m = 0;
      for (byte[] n : needles) m = Math.max(m, n.length);
      maxLen = m;
      List<List<Integer>> lens = new ArrayList<>();
      Map<Integer, List<Integer>> lf = new HashMap<>();
      for (int i = 0; i <= maxLen; i++) lens.add(new ArrayList<>());
      for (int i = 0; i < needles.length; i++) {
        lens.get(needles[i].length).add(i);
        lf.computeIfAbsent(needles[i].length * 256 + (needles[i][0] & 0xFF), k -> new ArrayList<>()).add(i);
      }
      byLen = new int[maxLen + 1][];
      byLenFirst = new int[maxLen + 1][][];
      for (int l = 0; l <= maxLen; l++) {
        if (!lens.get(l).isEmpty()) byLen[l] = toArray(lens.get(l));
      }
      for (Map.Entry<Integer, List<Integer>> e : lf.entrySet()) {
        int l = e.getKey() / 256, f = e.getKey() % 256;
        if (byLenFirst[l] == null) byLenFirst[l] = new int[256][];
        byLenFirst[l][f] = toArray(e.getValue());
      }
      found = new long[(needles.length + 63) / 64];
      for (byte[] nd : needles) {
        int h = sigOf(nd.length, nd[0] & 0xFF, nd[nd.length / 2] & 0xFF, nd[nd.length - 1] & 0xFF);
        sig[h >>> 6] |= 1L << h;
      }
      Map<Integer, List<Integer>> n2r = new HashMap<>();
      for (int r = 0; r < ruleName.length; r++) n2r.computeIfAbsent(ruleName[r], k -> new ArrayList<>()).add(r);
      nameToRules = new int[needles.length][];
      for (Map.Entry<Integer, List<Integer>> e : n2r.entrySet()) nameToRules[e.getKey()] = toArray(e.getValue());
      foundNames = new int[needles.length];
      utf8Pos = new int[65536];
      utf8Len = new int[65536];
    }

    void mark(int c) {
      long m = 1L << c;
      if ((found[c >>> 6] & m) == 0) {
        found[c >>> 6] |= m;
        if (nameToRules[c] != null) foundNames[foundNameCount++] = c;
      }
    }

    int evaluate() {
      int maybe = 0;
      for (int k = 0; k < foundNameCount; k++) {
        for (int r : nameToRules[foundNames[k]]) if (bit(ruleDesc[r]) && bit(ruleOwner[r])) maybe++;
      }
      return maybe;
    }

    /** Two passes: (1) walk the pool recording Utf8 (offset, length) only; (2) match those. */
    int walkTwoPass(byte[] b) {
      Arrays.fill(found, 0L);
      foundNameCount = 0;
      int count = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
      int off = 10, n = 0;
      for (int i = 1; i < count; i++) {
        int tag = b[off];
        switch (tag) {
          case 1: {
            int l = ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
            utf8Pos[n] = off + 3;
            utf8Len[n++] = l;
            off += 3 + l;
            break;
          }
          case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
            off += 5; break;
          case 5: case 6:
            off += 9; i++; break;
          case 7: case 8: case 16: case 19: case 20:
            off += 3; break;
          case 15:
            off += 4; break;
          default:
            throw new IllegalArgumentException("bad cp tag " + tag);
        }
      }
      for (int k = 0; k < n; k++) {
        int l = utf8Len[k];
        if (l > maxLen || l == 0) continue;
        int[][] byFirst = byLenFirst[l];
        if (byFirst == null) continue;
        int start = utf8Pos[k];
        int[] cands = byFirst[b[start] & 0xFF];
        if (cands == null) continue;
        for (int c : cands) if (exact(b, start, needles[c])) mark(c);
      }
      return foundNameCount == 0 ? 0 : evaluate();
    }

    static int id(Map<String, Integer> ids, List<byte[]> ns, String s) {
      Integer i = ids.get(s);
      if (i == null) {
        i = ns.size();
        ids.put(s, i);
        ns.add(s.getBytes(StandardCharsets.US_ASCII));
      }
      return i;
    }

    static int[] toArray(List<Integer> l) {
      int[] a = new int[l.size()];
      for (int i = 0; i < a.length; i++) a[i] = l.get(i);
      return a;
    }

    /** Returns the number of rules that may apply (0 = class ruled out). */
    int walk(byte[] b, boolean indexed) {
      Arrays.fill(found, 0L);
      foundNameCount = 0;
      boolean any = false;
      int count = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
      int off = 10;
      for (int i = 1; i < count; i++) {
        int tag = b[off];
        switch (tag) {
          case 1: {
            int l = ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
            int start = off + 3;
            if (l <= maxLen && l > 0) {
              int[] cands;
              if (SIG) {
                int h = sigOf(l, b[start] & 0xFF, b[start + l / 2] & 0xFF, b[start + l - 1] & 0xFF);
                if ((sig[h >>> 6] & (1L << h)) == 0) { off = start + l; break; }
              }
              if (indexed) {
                int[][] byFirst = byLenFirst[l];
                cands = byFirst == null ? null : byFirst[b[start] & 0xFF];
              } else {
                cands = byLen[l];
              }
              if (cands != null) {
                for (int c : cands) {
                  if (exact(b, start, needles[c])) {
                    mark(c);
                    any = true;
                  }
                }
              }
            }
            off = start + l;
            break;
          }
          case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
            off += 5; break;
          case 5: case 6:
            off += 9; i++; break;
          case 7: case 8: case 16: case 19: case 20:
            off += 3; break;
          case 15:
            off += 4; break;
          default:
            throw new IllegalArgumentException("bad cp tag " + tag);
        }
      }
      if (!any) return 0;
      return evaluate();
    }

    boolean bit(int c) {
      return (found[c >>> 6] & (1L << c)) != 0;
    }

    static boolean LAST_FIRST = false;
    static long compares, matches;
    static boolean COUNT = false;

    static boolean exact(byte[] b, int at, byte[] needle) {
      if (COUNT) compares++;
      int n = needle.length;
      if (LAST_FIRST && b[at + n - 1] != needle[n - 1]) return false;
      for (int j = 0; j < n; j++) if (b[at + j] != needle[j]) return false;
      if (COUNT) matches++;
      return true;
    }
  }

  static volatile long sink;

  public static void main(String[] args) throws Exception {
    List<byte[]> classes = new ArrayList<>();
    for (String path : Files.readAllLines(Paths.get(args[0]))) {
      if (path.trim().isEmpty()) continue;
      try (ZipFile zf = new ZipFile(path)) {
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
          ZipEntry ze = en.nextElement();
          String n = ze.getName();
          if (!n.endsWith(".class") || n.endsWith("module-info.class") || n.startsWith("META-INF/")) continue;
          try (InputStream in = zf.getInputStream(ze)) {
            classes.add(CpScan.readAll(in));
          }
        }
      }
    }
    // Real JDK call targets in the corpus.
    Set<String> calls = new LinkedHashSet<>();
    for (byte[] b : classes) {
      try {
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
          @Override
          public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
            return new MethodVisitor(Opcodes.ASM9) {
              @Override
              public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                if (owner.startsWith("java/") && !name.startsWith("<")) calls.add(owner + " " + name + " " + desc);
              }
            };
          }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      } catch (RuntimeException ignored) {
      }
    }
    List<String> pool = new ArrayList<>(calls);
    Collections.shuffle(pool, new Random(42));
    System.out.printf("corpus: %d classes; %d distinct JDK call targets to sample rules from%n%n",
        classes.size(), pool.size());

    byte[][] all = classes.toArray(new byte[0][]);
    System.out.printf("%6s %8s %10s %12s %12s %12s%n", "rules", "needles", "maybe%", "linear us", "indexed us", "2-pass us");
    for (int size : new int[] {5, 50, 500, 2000}) {
      List<String[]> rules = new ArrayList<>(Arrays.asList(BASE));
      for (int i = 0; rules.size() < size && i < pool.size(); i++) rules.add(pool.get(i).split(" "));
      RuleSet rs = new RuleSet(rules);
      int maybeClasses = 0;
      for (byte[] b : all) {
        try {
          int x = rs.walk(b, true);
          if (x != rs.walk(b, false) || x != rs.walkTwoPass(b)) throw new AssertionError("walks disagree");
          if (rs.walk(b, true) > 0) maybeClasses++;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
          maybeClasses++; // malformed: sound fallback is "maybe"
        }
      }
      double[] us = new double[3];
      for (int mode = 0; mode < 3; mode++) {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 8; round++) {
          long t0 = System.nanoTime(), acc = 0;
          for (byte[] b : all) {
            try {
              acc += mode == 2 ? rs.walkTwoPass(b) : rs.walk(b, mode == 1);
            } catch (RuntimeException e) {
              acc++;
            }
          }
          long dt = System.nanoTime() - t0;
          sink += acc;
          if (round >= 3) best = Math.min(best, dt);
        }
        us[mode] = best / 1000.0 / all.length;
      }
      RuleSet.COUNT = true; RuleSet.compares = 0; RuleSet.matches = 0;
      for (byte[] b : all) { try { rs.walk(b, true); } catch (RuntimeException e) { } }
      RuleSet.COUNT = false;
      long baseCompares = RuleSet.compares, baseMatches = RuleSet.matches;
      RuleSet.LAST_FIRST = true;
      long bestLF = Long.MAX_VALUE;
      for (int round = 0; round < 8; round++) {
        long t0 = System.nanoTime(), acc = 0;
        for (byte[] b : all) { try { acc += rs.walk(b, true); } catch (RuntimeException e) { acc++; } }
        long dt = System.nanoTime() - t0; sink += acc;
        if (round >= 3) bestLF = Math.min(bestLF, dt);
      }
      RuleSet.LAST_FIRST = false;
      RuleSet.SIG = true;
      RuleSet.COUNT = true; RuleSet.compares = 0; RuleSet.matches = 0;
      for (byte[] b : all) { try { rs.walk(b, true); } catch (RuntimeException e) { } }
      RuleSet.COUNT = false;
      long sigCompares = RuleSet.compares, sigMatches = RuleSet.matches;
      long bestSig = Long.MAX_VALUE;
      for (int round = 0; round < 8; round++) {
        long t0 = System.nanoTime(), acc = 0;
        for (byte[] b : all) { try { acc += rs.walk(b, true); } catch (RuntimeException e) { acc++; } }
        long dt = System.nanoTime() - t0; sink += acc;
        if (round >= 3) bestSig = Math.min(bestSig, dt);
      }
      for (byte[] b : all) { try { if (rs.walk(b, true) != rs.walk(b, false)) throw new AssertionError("sig changed result"); } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) { } }
      RuleSet.SIG = false;
      System.out.printf("        +signature filter: compares/class %.1f (matches %.1f), %.2f us%n",
          (double) sigCompares / all.length, (double) sigMatches / all.length, bestSig / 1000.0 / all.length);
      System.out.printf("        compares/class %.1f, matches/class %.1f, wasted %.0f%%; indexed+last-byte %.2f us%n",
          (double) baseCompares / all.length, (double) baseMatches / all.length,
          100.0 * (baseCompares - baseMatches) / Math.max(1, baseCompares), bestLF / 1000.0 / all.length);
      System.out.printf("%6d %8d %9.2f%% %12.2f %12.2f %12.2f%n",
          rules.size(), rs.needles.length, 100.0 * maybeClasses / all.length, us[0], us[1], us[2]);
    }
  }
}
