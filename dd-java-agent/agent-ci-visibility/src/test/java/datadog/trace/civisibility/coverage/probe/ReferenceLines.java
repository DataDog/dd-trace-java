package datadog.trace.civisibility.coverage.probe;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reference line coverage for tests: a call before every instruction records the line of the last
 * line table entry before it, so a line counts as soon as any of its code starts. It shares no
 * logic with the probes.
 */
public final class ReferenceLines {

  private static final Map<String, BitSet> LINES = new HashMap<>();

  private ReferenceLines() {}

  public static synchronized void hit(String sourcePath, int line) {
    LINES.computeIfAbsent(sourcePath, path -> new BitSet()).set(line);
  }

  /**
   * The package directory of a class followed by its source file, such as {@code okio/Buffer.kt}.
   */
  static String sourcePath(String className, String sourceFile) {
    return className.substring(0, className.lastIndexOf('/') + 1) + sourceFile;
  }

  static synchronized Map<String, BitSet> drain() {
    Map<String, BitSet> lines = new HashMap<>(LINES);
    LINES.clear();
    return lines;
  }

  /** Instruments a class so that it reports to {@link #hit}. */
  static byte[] instrument(byte[] source) {
    ClassReader reader = new ClassReader(source);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.EXPAND_FRAMES);
    String sourcePath = sourcePath(classNode.name, classNode.sourceFile);
    for (MethodNode method : classNode.methods) {
      int line = -1;
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof LineNumberNode) {
          line = ((LineNumberNode) insn).line;
        } else if (insn.getOpcode() >= 0 && line >= 0) {
          InsnList call = new InsnList();
          call.add(new LdcInsnNode(sourcePath));
          call.add(new LdcInsnNode(line));
          call.add(
              new MethodInsnNode(
                  Opcodes.INVOKESTATIC,
                  ReferenceLines.class.getName().replace('.', '/'),
                  "hit",
                  "(Ljava/lang/String;I)V",
                  false));
          method.instructions.insertBefore(insn, call);
        }
      }
      method.maxStack += 2;
    }
    ClassWriter writer = new ClassWriter(reader, 0);
    classNode.accept(writer);
    return writer.toByteArray();
  }
}
