package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Checks that instrumentation never gives a method a shape that HotSpot's JIT compilers refuse when
 * they accept the original: an exception handler also reached by normal control flow, or covering
 * code in its own first block (C1 bails out), or monitors that do not balance on every path,
 * exceptional ones included (C1 and C2 refuse the method, following {@code GenerateOopMap}). A
 * refused method stays in the interpreter.
 */
class ProbeJitShapeTest {

  private static final String HANDLER_REACHED_NORMALLY = "a handler is reached by normal flow";
  private static final String HANDLER_COVERS_ITSELF = "a handler covers its own first block";
  private static final String UNBALANCED_MONITORS = "monitors do not balance";

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
  void instrumentationKeepsMethodsCompilable(String markerClass) throws Exception {
    URL marker = getClass().getClassLoader().getResource(markerClass);
    assumeTrue(marker != null && "jar".equals(marker.getProtocol()), "not on the classpath");

    JarFile jar = ((JarURLConnection) marker.openConnection()).getJarFile();
    List<String> newProblems = new ArrayList<>();
    int instrumented = 0;
    for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
      JarEntry entry = entries.nextElement();
      String name = entry.getName();
      if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.contains("-info.")) {
        continue;
      }
      byte[] original;
      try (InputStream in = jar.getInputStream(entry)) {
        original = readAll(in);
      }
      int majorVersion = ((original[6] & 0xFF) << 8) | (original[7] & 0xFF);
      if (majorVersion < 49) {
        continue;
      }
      byte[] result =
          ProbeClassTransformer.instrument(
              original, getClass().getClassLoader(), majorVersion >= 50);
      if (result == null) {
        continue;
      }
      instrumented++;
      Map<String, MethodNode> originalMethods = methods(original);
      for (MethodNode method : methods(result).values()) {
        MethodNode before = originalMethods.get(method.name + method.desc);
        if (before == null) {
          continue;
        }
        Map<String, String> originalProblems = problems(before);
        if (originalProblems.containsKey(UNBALANCED_MONITORS)) {
          continue; // neither compiler accepts the original
        }
        Map<String, String> added = problems(method);
        added.keySet().removeAll(originalProblems.keySet());
        for (Map.Entry<String, String> problem : added.entrySet()) {
          newProblems.add(
              name
                  + " "
                  + method.name
                  + method.desc
                  + ": "
                  + problem.getKey()
                  + ", "
                  + problem.getValue());
        }
      }
    }

    assertEquals(new ArrayList<>(), newProblems);
    assertTrue(instrumented > 0, "no class was instrumented");
  }

  private static Map<String, MethodNode> methods(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    Map<String, MethodNode> methods = new HashMap<>();
    for (MethodNode method : node.methods) {
      methods.put(method.name + method.desc, method);
    }
    return methods;
  }

  /** The kinds of JIT-hostile shape a method has, each with where it first occurs. */
  static Map<String, String> problems(MethodNode method) {
    Map<String, String> problems = new TreeMap<>();
    AbstractInsnNode[] code = method.instructions.toArray();
    if (code.length == 0) {
      return problems;
    }
    InsnList instructions = method.instructions;
    List<TryCatchBlockNode> handlers = method.tryCatchBlocks;
    int[] start = new int[handlers.size()];
    int[] end = new int[handlers.size()];
    int[] entry = new int[handlers.size()];

    // the position of the instruction that runs first from each label
    Map<LabelNode, Integer> landing = new HashMap<>();
    for (int i = code.length - 1, next = -1; i >= 0; i--) {
      if (code[i].getOpcode() >= 0) {
        next = i;
      } else if (code[i] instanceof LabelNode) {
        landing.put((LabelNode) code[i], next);
      }
    }
    boolean[] blockStart = new boolean[code.length];
    boolean[] handlerEntry = new boolean[code.length];
    for (int h = 0; h < handlers.size(); h++) {
      TryCatchBlockNode handler = handlers.get(h);
      start[h] = instructions.indexOf(handler.start);
      end[h] = instructions.indexOf(handler.end);
      entry[h] = landing.get(handler.handler);
      blockStart[entry[h]] = true;
      handlerEntry[entry[h]] = true;
    }
    for (int i = 0; i < code.length; i++) {
      for (LabelNode target : jumpTargets(code[i])) {
        int landed = landing.get(target);
        blockStart[landed] = true;
        if (handlerEntry[landed]) {
          problems.putIfAbsent(HANDLER_REACHED_NORMALLY, "jump at " + i);
        }
      }
      if (code[i].getOpcode() >= 0 && !isUnconditional(code[i])) {
        int next = nextInstruction(code, i);
        if (next >= 0 && handlerEntry[next]) {
          problems.putIfAbsent(HANDLER_REACHED_NORMALLY, "fall-through at " + i);
        }
      }
    }

    // C1 parses each block once, so code in a handler's first block may not throw into it
    for (int h = 0; h < handlers.size(); h++) {
      for (int i = entry[h]; i >= 0; i = nextInstruction(code, i)) {
        if (i != entry[h] && blockStart[i]) {
          break;
        }
        int opcode = code[i].getOpcode();
        if (start[h] < i
            && i < end[h]
            && opcode != Opcodes.MONITOREXIT
            && MethodProbeInserter.mayThrow(code[i])) {
          problems.putIfAbsent(HANDLER_COVERS_ITSELF, "handler " + h + " at " + i);
        }
        if (isUnconditional(code[i]) || code[i] instanceof JumpInsnNode) {
          break;
        }
      }
    }

    int[] depth = new int[code.length];
    Arrays.fill(depth, -1);
    Deque<int[]> work = new ArrayDeque<>();
    int first = nextInstruction(code, -1);
    if (first >= 0) {
      work.push(new int[] {first, 0});
    }
    while (!work.isEmpty()) {
      int[] state = work.pop();
      int i = state[0];
      int monitors = state[1];
      if (depth[i] >= 0) {
        if (depth[i] != monitors) {
          problems.putIfAbsent(UNBALANCED_MONITORS, "merge at " + i);
        }
        continue;
      }
      depth[i] = monitors;
      AbstractInsnNode insn = code[i];
      int opcode = insn.getOpcode();
      int after = monitors;
      boolean trap;
      if (opcode == Opcodes.LDC) {
        trap = true;
      } else if (opcode == Opcodes.MONITOREXIT) {
        // GenerateOopMap takes a monitorexit with a monitor held to succeed
        trap = monitors == 0;
        if (monitors == 0) {
          problems.putIfAbsent(UNBALANCED_MONITORS, "monitorexit without a monitor at " + i);
        } else {
          after = monitors - 1;
        }
      } else if (isReturn(opcode)) {
        trap = monitors != 0;
        if (monitors != 0) {
          problems.putIfAbsent(UNBALANCED_MONITORS, "return with a monitor held at " + i);
        }
      } else {
        trap = MethodProbeInserter.mayThrow(insn);
        if (opcode == Opcodes.MONITORENTER) {
          after = monitors + 1;
        }
      }
      if (trap && (!handlers.isEmpty() || monitors != 0)) {
        boolean caughtByAll = false;
        for (int h = 0; h < handlers.size() && !caughtByAll; h++) {
          if (start[h] < i && i < end[h]) {
            work.push(new int[] {entry[h], monitors});
            caughtByAll = handlers.get(h).type == null;
          }
        }
        if (!caughtByAll && monitors != 0) {
          problems.putIfAbsent(UNBALANCED_MONITORS, "throw with a monitor held at " + i);
        }
      }
      for (LabelNode target : jumpTargets(insn)) {
        work.push(new int[] {landing.get(target), after});
      }
      if (!isUnconditional(insn)) {
        int next = nextInstruction(code, i);
        if (next >= 0) {
          work.push(new int[] {next, after});
        }
      }
    }
    return problems;
  }

  private static int nextInstruction(AbstractInsnNode[] code, int i) {
    for (int k = i + 1; k < code.length; k++) {
      if (code[k].getOpcode() >= 0) {
        return k;
      }
    }
    return -1;
  }

  private static List<LabelNode> jumpTargets(AbstractInsnNode insn) {
    List<LabelNode> targets = new ArrayList<>();
    if (insn instanceof JumpInsnNode) {
      targets.add(((JumpInsnNode) insn).label);
    } else if (insn instanceof TableSwitchInsnNode) {
      targets.add(((TableSwitchInsnNode) insn).dflt);
      targets.addAll(((TableSwitchInsnNode) insn).labels);
    } else if (insn instanceof LookupSwitchInsnNode) {
      targets.add(((LookupSwitchInsnNode) insn).dflt);
      targets.addAll(((LookupSwitchInsnNode) insn).labels);
    }
    return targets;
  }

  private static boolean isReturn(int opcode) {
    return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
  }

  private static boolean isUnconditional(AbstractInsnNode insn) {
    int opcode = insn.getOpcode();
    return opcode == Opcodes.GOTO
        || opcode == Opcodes.ATHROW
        || isReturn(opcode)
        || insn instanceof TableSwitchInsnNode
        || insn instanceof LookupSwitchInsnNode;
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
