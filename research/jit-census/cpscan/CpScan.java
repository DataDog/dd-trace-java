import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Prototype: a constant-pool scan that ignores class-file structure, used as a one-sided pre-check
 * before a real parse.
 *
 * <p>For each rule (owner.name + descriptor), a class can reference the method only if both the
 * name bytes and the descriptor bytes appear in the class file (CONSTANT_Utf8 entries are
 * contiguous, and modified UTF-8 is plain ASCII for ASCII identifiers). So "absent" is exact and
 * "present" means maybe.
 *
 * <p>Reports, over a corpus of jars: the "maybe" rate per rule vs ground truth (an actual invoke
 * of owner.name+desc found by ASM), soundness (no false negatives), and the cost per class for the
 * scan vs `new ClassReader(bytes)` (constant pool indexing only) vs a full method-body visit.
 *
 * <p>Usage: java -cp asm.jar:. CpScan corpus.txt
 */
public class CpScan {
  static final class Rule {
    final String owner, name, desc;
    final byte[] nameBytes, descBytes;

    Rule(String owner, String name, String desc) {
      this.owner = owner;
      this.name = name;
      this.desc = desc;
      this.nameBytes = name.getBytes(StandardCharsets.US_ASCII);
      this.descBytes = desc.getBytes(StandardCharsets.US_ASCII);
    }

    boolean maybe(byte[] b) {
      // Check the rarer needle (descriptor) first.
      return indexOf(b, descBytes) >= 0 && indexOf(b, nameBytes) >= 0;
    }

    public String toString() {
      return owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
    }
  }

  static final Rule[] RULES = {
    new Rule("java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;"),
    new Rule("java/lang/String", "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
    new Rule("java/lang/String", "matches", "(Ljava/lang/String;)Z"),
    new Rule("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"),
    new Rule("java/util/regex/Pattern", "compile", "(Ljava/lang/String;)Ljava/util/regex/Pattern;"),
  };

  static int indexOf(byte[] hay, byte[] needle) {
    final byte first = needle[0];
    final int max = hay.length - needle.length;
    outer:
    for (int i = 0; i <= max; i++) {
      if (hay[i] != first) continue;
      for (int j = 1; j < needle.length; j++) {
        if (hay[i + j] != needle[j]) continue outer;
      }
      return i;
    }
    return -1;
  }

  /**
   * Structure-unaware, single pass: every rule's descriptor starts with '(', so look only at '('
   * positions and compare against all descriptors there. Names are checked only for rules whose
   * descriptor hit (rare). Returns a bitmask of "maybe" rules.
   */
  static int scanSinglePass(byte[] b) {
    int descHits = 0;
    final int len = b.length;
    for (int i = 0; i < len; i++) {
      if (b[i] != '(') continue;
      for (int r = 0; r < RULES.length; r++) {
        if ((descHits & (1 << r)) == 0 && regionEquals(b, i, RULES[r].descBytes)) descHits |= 1 << r;
      }
    }
    int maybe = 0;
    for (int r = 0; r < RULES.length; r++) {
      if ((descHits & (1 << r)) != 0 && indexOf(b, RULES[r].nameBytes) >= 0) maybe |= 1 << r;
    }
    return maybe;
  }

  static boolean regionEquals(byte[] b, int at, byte[] needle) {
    if (at + needle.length > b.length) return false;
    for (int j = 1; j < needle.length; j++) if (b[at + j] != needle[j]) return false;
    return true;
  }

  /**
   * Structure-aware but minimal: walk the constant pool entries (as ClassReader's constructor
   * does) and compare only CONSTANT_Utf8 entries, by exact length first. Returns a bitmask of
   * "maybe" rules (both the name and the descriptor exist as Utf8 entries).
   */
  static int scanConstantPool(byte[] b) {
    int names = 0, descs = 0;
    int count = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
    int off = 10;
    for (int i = 1; i < count; i++) {
      int tag = b[off];
      switch (tag) {
        case 1: {
          int l = ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
          int start = off + 3;
          for (int r = 0; r < RULES.length; r++) {
            Rule rule = RULES[r];
            if (l == rule.nameBytes.length && exact(b, start, rule.nameBytes)) names |= 1 << r;
            if (l == rule.descBytes.length && exact(b, start, rule.descBytes)) descs |= 1 << r;
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
    return names & descs;
  }

  static boolean exact(byte[] b, int at, byte[] needle) {
    for (int j = 0; j < needle.length; j++) if (b[at + j] != needle[j]) return false;
    return true;
  }

  static boolean[] truth(byte[] b) {
    final boolean[] hit = new boolean[RULES.length];
    new ClassReader(b)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitMethodInsn(
                      int op, String owner, String name, String desc, boolean itf) {
                    for (int r = 0; r < RULES.length; r++) {
                      Rule rule = RULES[r];
                      if (rule.owner.equals(owner)
                          && rule.name.equals(name)
                          && rule.desc.equals(desc)) {
                        hit[r] = true;
                      }
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return hit;
  }

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    return out.toByteArray();
  }

  static volatile long sink;

  public static void main(String[] args) throws Exception {
    List<byte[]> classes = new ArrayList<>();
    long totalBytes = 0;
    int jars = 0;
    for (String path : Files.readAllLines(Paths.get(args[0]))) {
      if (path.trim().isEmpty()) continue;
      try (ZipFile zf = new ZipFile(path)) {
        jars++;
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
          ZipEntry ze = en.nextElement();
          String n = ze.getName();
          if (!n.endsWith(".class") || n.endsWith("module-info.class") || n.startsWith("META-INF/")) {
            continue;
          }
          try (InputStream in = zf.getInputStream(ze)) {
            byte[] b = readAll(in);
            classes.add(b);
            totalBytes += b.length;
          }
        }
      }
    }
    System.out.printf(
        "corpus: %d jars, %d classes, avg %.1f KB/class%n%n",
        jars, classes.size(), totalBytes / 1024.0 / classes.size());

    // Selectivity and soundness.
    int[] maybe = new int[RULES.length], real = new int[RULES.length], falseNeg = new int[RULES.length];
    int anyMaybe = 0, anyReal = 0, unparsable = 0, mismatch1 = 0, falseNeg2 = 0, anyMaybe2 = 0;
    for (byte[] b : classes) {
      boolean[] t;
      try {
        t = truth(b);
      } catch (RuntimeException e) {
        unparsable++;
        continue;
      }
      boolean am = false, ar = false;
      for (int r = 0; r < RULES.length; r++) {
        boolean m = RULES[r].maybe(b);
        if (m) maybe[r]++;
        if (t[r]) real[r]++;
        if (t[r] && !m) falseNeg[r]++;
        if (m != ((scanSinglePass(b) >> r & 1) != 0)) mismatch1++;
        if (t[r] && (scanConstantPool(b) >> r & 1) == 0) falseNeg2++;
        am |= m;
        ar |= t[r];
      }
      if (am) anyMaybe++;
      if (scanConstantPool(b) != 0) anyMaybe2++;
      if (ar) anyReal++;
    }
    int n = classes.size() - unparsable;
    System.out.printf("%-16s %8s %8s %8s %10s%n", "rule", "maybe%", "real%", "falseNeg", "precision");
    for (int r = 0; r < RULES.length; r++) {
      System.out.printf(
          "%-16s %7.2f%% %7.2f%% %8d %9.0f%%%n",
          RULES[r], 100.0 * maybe[r] / n, 100.0 * real[r] / n, falseNeg[r],
          maybe[r] == 0 ? 100.0 : 100.0 * real[r] / maybe[r]);
    }
    System.out.printf(
        "%-16s %7.2f%% %7.2f%%   (classes needing a real parse vs. classes with a real hit)%n",
        "any rule", 100.0 * anyMaybe / n, 100.0 * anyReal / n);
    System.out.printf("single-pass scan disagrees with per-rule scan: %d; cp-walk false negatives: %d; cp-walk any-maybe %.2f%%%n",
        mismatch1, falseNeg2, 100.0 * anyMaybe2 / n);
    if (unparsable > 0) System.out.printf("(%d classes ASM could not parse, skipped)%n", unparsable);

    // Cost per class. Several timed rounds after warmup; report the best round.
    byte[][] all = classes.toArray(new byte[0][]);
    String[] names = {"naive scan, 5 rules x 2 needles", "new ClassReader only", "full visit (no debug/frames)",
        "unaware single pass ('(' anchor)", "constant-pool walk, Utf8 compare"};
    for (int mode = 0; mode < 5; mode++) {
      long best = Long.MAX_VALUE;
      for (int round = 0; round < 8; round++) {
        long t0 = System.nanoTime();
        long acc = 0;
        for (byte[] b : all) {
          try {
            if (mode == 0) {
              for (Rule rule : RULES) if (rule.maybe(b)) acc++;
            } else if (mode == 1) {
              acc += new ClassReader(b).getItemCount();
            } else if (mode == 2) {
              boolean[] t = truth(b);
              if (t[0]) acc++;
            } else if (mode == 3) {
              acc += scanSinglePass(b);
            } else {
              acc += scanConstantPool(b);
            }
          } catch (RuntimeException e) {
            // skip unparsable
          }
        }
        long dt = System.nanoTime() - t0;
        sink += acc;
        if (round >= 3) best = Math.min(best, dt);
      }
      System.out.printf("%-30s %8.2f us/class  (%.0f MB/s)%n",
          names[mode], best / 1000.0 / all.length, totalBytes / (best / 1e9) / 1e6);
    }
  }
}
