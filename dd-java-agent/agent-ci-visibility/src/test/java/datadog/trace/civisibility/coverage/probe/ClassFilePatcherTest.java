package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Patches every class of third-party jars with {@link ClassFilePatcher}, instruments it with ASM as
 * well, and checks that both give the same code and the same probes. The jars cover the bytecode of
 * javac, kotlinc, scalac and groovyc.
 */
class ClassFilePatcherTest {

  /** Above SIPUSH's range, so that the class id is pushed with LDC. */
  private static final int CLASS_ID = 40000;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "kotlin/Unit.class",
        "kotlinx/coroutines/Job.class",
        "scala/Option.class",
        "groovy/lang/GroovyObject.class",
        "okhttp3/OkHttpClient.class",
        "okio/Buffer.class",
        "org/jacoco/core/JaCoCo.class",
        "freemarker/template/Template.class",
        "com/fasterxml/jackson/databind/ObjectMapper.class",
        "org/mockito/Mockito.class",
        "org/spockframework/runtime/model/FeatureInfo.class",
        "org/objectweb/asm/ClassReader.class",
      })
  void patchedClassesMatchAsmInstrumentation(String markerClass) throws Exception {
    URL marker = getClass().getClassLoader().getResource(markerClass);
    assumeTrue(marker != null && "jar".equals(marker.getProtocol()), "not on the classpath");

    JarFile jar = ((JarURLConnection) marker.openConnection()).getJarFile();
    Map<String, Integer> fallbacks = new TreeMap<>();
    List<String> differences = new ArrayList<>();
    int classes = 0;
    int candidates = 0;
    int patched = 0;
    int largeMethods = 0;
    for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
      JarEntry entry = entries.nextElement();
      String name = entry.getName();
      if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
        continue;
      }
      byte[] original;
      try (InputStream in = jar.getInputStream(entry)) {
        original = readAll(in);
      }
      classes++;
      int majorVersion = ((original[6] & 0xFF) << 8) | (original[7] & 0xFF);
      if (majorVersion < Opcodes.V1_6) {
        fallbacks.merge("class file before Java 6", 1, Integer::sum);
        continue;
      }
      ClassFile classFile = new ClassFile(original);
      if ((classFile.access & Opcodes.ACC_MODULE) != 0) {
        continue;
      }
      candidates++;

      ProbeClassTransformer.Instrumented fromPatcher = null;
      RuntimeException patcherFailure = null;
      try {
        fromPatcher = ClassFilePatcher.instrument(classFile, CLASS_ID);
      } catch (UnsupportedClassException e) {
        fallbacks.merge(e.reason, 1, Integer::sum);
        continue;
      } catch (RuntimeException e) {
        patcherFailure = e;
      }
      ProbeClassTransformer.Instrumented fromAsm = null;
      RuntimeException asmFailure = null;
      try {
        fromAsm = ProbeClassTransformer.instrumentWithAsm(original, true, CLASS_ID);
      } catch (RuntimeException e) {
        asmFailure = e;
      }

      if (patcherFailure != null || asmFailure != null) {
        if (patcherFailure == null || asmFailure == null) {
          differences.add(name + ": patcher " + describe(patcherFailure) + ", ASM " + asmFailure);
        }
        continue;
      }
      if (fromPatcher == null || fromAsm == null) {
        if (fromPatcher != fromAsm) {
          differences.add(name + ": instrumented only by " + (fromAsm == null ? "patcher" : "ASM"));
        }
        continue;
      }
      patched++;
      if (!describe(fromPatcher.probes).equals(describe(fromAsm.probes))) {
        differences.add(name + ": different probes");
      }
      if (!fromPatcher.className.equals(fromAsm.className)
          || !fromPatcher.sourceFile.equals(fromAsm.sourceFile)
          || !String.valueOf(fromPatcher.sourceDebug).equals(String.valueOf(fromAsm.sourceDebug))) {
        differences.add(name + ": different class name, source file or source map");
      }
      Map<String, MethodNode> patchedMethods = methods(fromPatcher.bytes);
      Map<String, MethodNode> asmMethods = methods(fromAsm.bytes);
      if (!patchedMethods.keySet().equals(asmMethods.keySet())) {
        differences.add(name + ": different methods");
        continue;
      }
      for (Map.Entry<String, MethodNode> method : patchedMethods.entrySet()) {
        String patchedText = text(method.getValue());
        String asmText = text(asmMethods.get(method.getKey()));
        if (patchedText.equals(asmText)) {
          continue;
        }
        if (maxCodeSize(method.getValue()) > Short.MAX_VALUE) {
          largeMethods++; // ASM replaces backward jumps of such methods
        } else {
          differences.add(
              name + " " + method.getKey() + ":\n" + firstDifference(patchedText, asmText));
        }
      }
    }

    System.out.println(
        jar.getName()
            + ": "
            + classes
            + " classes, "
            + patched
            + " patched, "
            + largeMethods
            + " large methods differ, left to ASM: "
            + fallbacks);
    assertEquals(
        new ArrayList<>(),
        differences.subList(0, Math.min(differences.size(), 10)),
        differences.size() + " differences");
    assumeTrue(
        candidates > 0, "no class file the patcher takes: all predate Java 6 or are modules");
    assertTrue(patched > 0, "no class was patched");
  }

  private static String describe(Throwable e) {
    if (e == null) {
      return "null";
    }
    StackTraceElement[] frames = e.getStackTrace();
    return e + " at " + Arrays.asList(frames).subList(0, Math.min(frames.length, 6));
  }

  @SuppressWarnings("unchecked")
  private static String describe(ClassProbesBuilder probes) throws Exception {
    List<int[]> probeLines = (List<int[]>) field(probes, "probeLines");
    int[] probeParent = (int[]) field(probes, "probeParent");
    List<int[]> nodeLines = (List<int[]>) field(probes, "nodeLines");
    int[] nodeParent = (int[]) field(probes, "nodeParent");
    StringBuilder text = new StringBuilder("init ").append(field(probes, "initStart")).append('\n');
    for (int i = 0; i < probeLines.size(); i++) {
      text.append("probe ")
          .append(Arrays.toString(probeLines.get(i)))
          .append(" in ")
          .append(probeParent[i])
          .append('\n');
    }
    for (int i = 0; i < nodeLines.size(); i++) {
      text.append("node ")
          .append(Arrays.toString(nodeLines.get(i)))
          .append(" in ")
          .append(nodeParent[i])
          .append('\n');
    }
    return text.toString();
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  /** The methods of a class, with frames expanded so that their encoding does not matter. */
  private static Map<String, MethodNode> methods(byte[] bytes) {
    ClassNode classNode = new ClassNode();
    new ClassReader(bytes).accept(classNode, ClassReader.EXPAND_FRAMES);
    Map<String, MethodNode> methods = new LinkedHashMap<>();
    for (MethodNode method : classNode.methods) {
      methods.put(method.name + method.desc, method);
    }
    return methods;
  }

  private static String text(MethodNode method) {
    Textifier textifier = new Textifier();
    method.accept(new TraceMethodVisitor(textifier));
    StringWriter out = new StringWriter();
    textifier.print(new PrintWriter(out));
    return out.toString();
  }

  /** The size bound over which the ASM transformer replaces backward jumps. */
  private static int maxCodeSize(MethodNode method) {
    int size = 0;
    for (AbstractInsnNode insn = method.instructions.getFirst();
        insn != null;
        insn = insn.getNext()) {
      switch (insn.getType()) {
        case AbstractInsnNode.LABEL:
        case AbstractInsnNode.LINE:
        case AbstractInsnNode.FRAME:
          break;
        case AbstractInsnNode.INSN:
          size += 1;
          break;
        case AbstractInsnNode.JUMP_INSN:
          size += 8;
          break;
        case AbstractInsnNode.TABLESWITCH_INSN:
          size += 16 + 4 * ((TableSwitchInsnNode) insn).labels.size();
          break;
        case AbstractInsnNode.LOOKUPSWITCH_INSN:
          size += 12 + 8 * ((LookupSwitchInsnNode) insn).labels.size();
          break;
        default:
          size += 6;
      }
    }
    return size;
  }

  private static String firstDifference(String patched, String asm) {
    String[] patchedLines = patched.split("\n");
    String[] asmLines = asm.split("\n");
    int line = 0;
    while (line < patchedLines.length
        && line < asmLines.length
        && patchedLines[line].equals(asmLines[line])) {
      line++;
    }
    int from = Math.max(0, line - 4);
    return "patcher:\n"
        + String.join(
            "\n", Arrays.copyOfRange(patchedLines, from, Math.min(patchedLines.length, line + 4)))
        + "\nASM:\n"
        + String.join(
            "\n", Arrays.copyOfRange(asmLines, from, Math.min(asmLines.length, line + 4)));
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int read; (read = in.read(buffer)) != -1; ) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }
}
