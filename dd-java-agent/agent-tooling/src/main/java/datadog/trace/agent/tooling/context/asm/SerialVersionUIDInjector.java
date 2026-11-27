package datadog.trace.agent.tooling.context.asm;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pins a {@code Serializable} class's default {@code serialVersionUID} across a structural change.
 *
 * <p>The default value hashes the class's interfaces and non-private members, so adding an
 * interface or methods to a class that declares no {@code serialVersionUID} would change it, and
 * deserialization between processes with and without the change would fail. Feed this the class's
 * original {@code visit}, {@code visitField}, {@code visitMethod} and {@code visitInnerClass} calls
 * (before the change is applied), then call {@link #injectSerialVersionUID} at {@code visitEnd} to
 * write the original value as a field if the class declares none.
 */
public final class SerialVersionUIDInjector extends SerialVersionUIDAdder {
  private static final Logger log = LoggerFactory.getLogger(SerialVersionUIDInjector.class);

  public SerialVersionUIDInjector() {
    super(Opcodes.ASM8, null);
  }

  /** Writes the original default {@code serialVersionUID} to {@code transformer}, if needed. */
  public void injectSerialVersionUID(
      final TypeDescription instrumentedType, final ClassVisitor transformer) {
    if (!hasSVUID()) {
      try {
        transformer.visitField(
            Opcodes.ACC_FINAL | Opcodes.ACC_STATIC, "serialVersionUID", "J", null, computeSVUID());
      } catch (final Exception e) {
        log.debug("Failed to add serialVersionUID to {}", instrumentedType.getName(), e);
      }
    }
  }
}
