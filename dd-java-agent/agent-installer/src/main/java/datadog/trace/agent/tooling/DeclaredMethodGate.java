package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.matcher.ConstantPoolSieve;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import java.util.BitSet;

/**
 * Per-thread result of sieving the type currently being matched for advised method names.
 *
 * <p>{@link CombiningMatcher} prepares the gate once per type; gated hierarchy matchers then ask
 * whether the type can declare any of their advised methods. When the type's bytes aren't known (or
 * don't belong to the type being matched, or can't be walked) the gate stays open, so it never
 * excludes a type it hasn't fully scanned.
 */
final class DeclaredMethodGate {
  private static final ThreadLocal<DeclaredMethodGate> current =
      ThreadLocal.withInitial(DeclaredMethodGate::new);

  private final BitSet present = new BitSet();
  private boolean scanned;

  private DeclaredMethodGate() {}

  /** Scans the bytes of the type being matched, if they're known and belong to that type. */
  static void prepare(ConstantPoolSieve sieve, String typeName) {
    DeclaredMethodGate gate = current.get();
    gate.present.clear();
    gate.scanned = false;
    if (null != sieve) {
      byte[] bytecode = TypePoolFacade.currentTargetBytecode();
      if (null != bytecode && typeName.equals(TypePoolFacade.currentTargetName())) {
        gate.scanned = sieve.scan(bytecode, gate.present);
      }
    }
  }

  /** Opens the gate, e.g. when re-matching loaded classes without their bytes. */
  static void open() {
    DeclaredMethodGate gate = current.get();
    gate.present.clear();
    gate.scanned = false;
  }

  /**
   * Returns {@code true} if the current type can't declare any of the given names, i.e. its
   * constant pool was fully scanned and none of them is present. An id of -1 counts as present.
   */
  static boolean excludes(int[] nameIds) {
    DeclaredMethodGate gate = current.get();
    if (!gate.scanned) {
      return false;
    }
    for (int id : nameIds) {
      if (id < 0 || gate.present.get(id)) {
        return false;
      }
    }
    return true;
  }
}
