import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Lowering instrumentation matchers into constant-pool pre-checks.
 *
 * <p>A hierarchy matcher of the form "subtype of T that declares method name(desc)" needs the class
 * to declare the method, so the method's name and descriptor must both be Utf8 entries in the
 * class's constant pool. That pre-check is sound in the "no" direction and needs nothing but the
 * class's own bytes; the hierarchy question (which needs other classes) is asked only of classes
 * that pass.
 *
 * <p>Per matcher, over the corpus: classes passing the pre-check, classes that really declare the
 * method (ASM), and classes that declare it and are subtypes of T (hierarchy resolved over the
 * corpus plus the running JDK). Also checks soundness: no true match may fail the pre-check.
 *
 * <p>Usage: java -cp asm.jar:. CpScanInstr jar-paths.txt
 */
public class CpScanInstr {
  static final class Matcher {
    final String label, type, name, desc;

    Matcher(String label, String type, String name, String desc) {
      this.label = label;
      this.type = type;
      this.name = name;
      this.desc = desc;
    }
  }

  static final Matcher[] MATCHERS = {
    new Matcher("servlet service", "javax/servlet/Servlet", "service",
        "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V"),
    new Matcher("http servlet service", "javax/servlet/http/HttpServlet", "service",
        "(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V"),
    new Matcher("jakarta servlet service", "jakarta/servlet/Servlet", "service",
        "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"),
    new Matcher("filter doFilter", "javax/servlet/Filter", "doFilter",
        "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;Ljavax/servlet/FilterChain;)V"),
    new Matcher("jdbc executeQuery(sql)", "java/sql/Statement", "executeQuery",
        "(Ljava/lang/String;)Ljava/sql/ResultSet;"),
    new Matcher("jdbc execute(sql)", "java/sql/Statement", "execute", "(Ljava/lang/String;)Z"),
    new Matcher("jdbc prepared executeQuery", "java/sql/PreparedStatement", "executeQuery",
        "()Ljava/sql/ResultSet;"),
    new Matcher("netty channelRead", "io/netty/channel/ChannelInboundHandler", "channelRead",
        "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V"),
    new Matcher("okhttp intercept", "okhttp3/Interceptor", "intercept",
        "(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;"),
    new Matcher("kafka poll(Duration)", "org/apache/kafka/clients/consumer/Consumer", "poll",
        "(Ljava/time/Duration;)Lorg/apache/kafka/clients/consumer/ConsumerRecords;"),
    new Matcher("callable call", "java/util/concurrent/Callable", "call", "()Ljava/lang/Object;"),
    new Matcher("runnable run", "java/lang/Runnable", "run", "()V"),
  };

  static final class Info {
    String superName;
    String[] interfaces;
    List<String> methods = new ArrayList<>(); // name + desc
  }

  static final Map<String, Info> infos = new HashMap<>();

  static Info info(String name) {
    Info i = infos.get(name);
    if (i == null && !infos.containsKey(name)) {
      // Fall back to the running JDK (or the classpath) for types outside the corpus.
      try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
        if (in != null) i = parse(CpScan.readAll(in));
      } catch (Exception ignored) {
      }
      infos.put(name, i);
    }
    return i;
  }

  static Info parse(byte[] b) {
    Info info = new Info();
    new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public void visit(int v, int a, String n, String s, String sup, String[] itfs) {
        info.superName = sup;
        info.interfaces = itfs;
      }

      @Override
      public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
        info.methods.add(n + d);
        return null;
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return info;
  }

  static boolean isSubtype(String name, String target, int depth) {
    if (name == null || depth > 64) return false;
    if (name.equals(target)) return true;
    Info i = info(name);
    if (i == null) return false; // unresolvable (missing dependency): treat as not a subtype
    if (isSubtype(i.superName, target, depth + 1)) return true;
    if (i.interfaces != null) for (String itf : i.interfaces) if (isSubtype(itf, target, depth + 1)) return true;
    return false;
  }

  static boolean hasUtf8(byte[] b, byte[] needle) {
    int count = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
    int off = 10;
    for (int i = 1; i < count; i++) {
      int tag = b[off];
      switch (tag) {
        case 1: {
          int l = ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
          if (l == needle.length && CpScanScale.RuleSet.exact(b, off + 3, needle)) return true;
          off += 3 + l;
          break;
        }
        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: off += 5; break;
        case 5: case 6: off += 9; i++; break;
        case 7: case 8: case 16: case 19: case 20: off += 3; break;
        case 15: off += 4; break;
        default: throw new IllegalArgumentException("bad cp tag " + tag);
      }
    }
    return false;
  }

  public static void main(String[] args) throws Exception {
    List<byte[]> bytes = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (String path : Files.readAllLines(Paths.get(args[0]))) {
      if (path.trim().isEmpty()) continue;
      try (ZipFile zf = new ZipFile(path)) {
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
          ZipEntry ze = en.nextElement();
          String n = ze.getName();
          if (!n.endsWith(".class") || n.endsWith("module-info.class") || n.startsWith("META-INF/")) continue;
          try (InputStream in = zf.getInputStream(ze)) {
            byte[] b = CpScan.readAll(in);
            try {
              Info i = parse(b);
              String cn = new ClassReader(b).getClassName();
              if (!infos.containsKey(cn)) {
                infos.put(cn, i);
                bytes.add(b);
                names.add(cn);
              }
            } catch (RuntimeException ignored) {
            }
          }
        }
      }
    }
    int n = bytes.size();
    System.out.printf("corpus: %d distinct classes%n%n", n);
    System.out.printf("%-28s %9s %9s %9s %9s%n", "matcher", "pre-check", "declares", "true", "falseNeg");
    boolean[] anyPass = new boolean[n];
    int anyTrue = 0;
    boolean[] isTrueAny = new boolean[n];
    for (Matcher m : MATCHERS) {
      byte[] nn = m.name.getBytes(StandardCharsets.US_ASCII), dn = m.desc.getBytes(StandardCharsets.US_ASCII);
      int pass = 0, declares = 0, truth = 0, falseNeg = 0;
      for (int c = 0; c < n; c++) {
        byte[] b = bytes.get(c);
        boolean p;
        try {
          p = hasUtf8(b, dn) && hasUtf8(b, nn);
        } catch (RuntimeException e) {
          p = true; // malformed: sound fallback
        }
        boolean d = infos.get(names.get(c)).methods.contains(m.name + m.desc);
        boolean t = d && isSubtype(names.get(c), m.type, 0);
        if (p) pass++;
        if (d) declares++;
        if (t) truth++;
        if (t && !p) falseNeg++;
        if (p && m != MATCHERS[MATCHERS.length - 1]) anyPass[c] = true; // excluding Runnable
        if (t) isTrueAny[c] = true;
      }
      System.out.printf("%-28s %8.2f%% %8.2f%% %8.2f%% %9d%n", m.label,
          100.0 * pass / n, 100.0 * declares / n, 100.0 * truth / n, falseNeg);
    }
    int ap = 0;
    for (int c = 0; c < n; c++) {
      if (anyPass[c]) ap++;
      if (isTrueAny[c]) anyTrue++;
    }
    System.out.printf("%nall matchers except Runnable: %.2f%% of classes need a hierarchy check (vs 100%% without the pre-check)%n",
        100.0 * ap / n);
  }
}
