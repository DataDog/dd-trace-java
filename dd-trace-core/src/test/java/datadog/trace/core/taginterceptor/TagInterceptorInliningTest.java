package datadog.trace.core.taginterceptor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

/**
 * Pins a code-shape property that C2 depends on but no other test can see.
 *
 * <p>{@code interceptTag(DDSpanContext, long, Object)} must stay too big for C2 to inline (more
 * than {@code FreqInlineSize}, 325 bytes of bytecode). DDSpanContext.setTag calls it only for
 * intercepted tags; if it inlines into setTag's own compiled code, the profiled handler bodies come
 * with it and push setTag past {@code InlineSmallCode}, so callers stop inlining setTag and a
 * constant id no longer folds its interception test away.
 */
class TagInterceptorInliningTest {
  private static final int FREQ_INLINE_SIZE = 325;

  @Test
  void interceptTagByIdStaysTooBigToInline() throws IOException {
    int length =
        codeLength("interceptTag", "(Ldatadog/trace/core/DDSpanContext;JLjava/lang/Object;)Z");

    assertTrue(
        length > FREQ_INLINE_SIZE,
        "interceptTag(span, long, value) is "
            + length
            + " bytes of bytecode, small enough for C2 to inline into DDSpanContext.setTag; keep"
            + " its handlers in the switch (see this test's Javadoc)");
  }

  /** The {@code code_length} of a method's Code attribute, read from the class file. */
  private static int codeLength(String name, String descriptor) throws IOException {
    ClassReader reader;
    try (InputStream in = TagInterceptor.class.getResourceAsStream("TagInterceptor.class")) {
      reader = new ClassReader(in);
    }
    char[] buf = new char[reader.getMaxStringLength()];
    int offset = reader.header + 6; // access_flags, this_class, super_class
    offset += 2 + 2 * reader.readUnsignedShort(offset); // interfaces
    int fields = reader.readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < fields; ++i) {
      offset = skipAttributes(reader, offset + 6); // access_flags, name, descriptor
    }
    int methods = reader.readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < methods; ++i) {
      if (reader.readUTF8(offset + 2, buf).equals(name)
          && reader.readUTF8(offset + 4, buf).equals(descriptor)) {
        int attributes = reader.readUnsignedShort(offset + 6);
        int attribute = offset + 8;
        for (int j = 0; j < attributes; ++j) {
          if ("Code".equals(reader.readUTF8(attribute, buf))) {
            return reader.readInt(attribute + 10); // name, length, max_stack, max_locals
          }
          attribute += 6 + reader.readInt(attribute + 2);
        }
      }
      offset = skipAttributes(reader, offset + 6);
    }
    throw new AssertionError(name + descriptor + " not found");
  }

  /** Skips the attributes whose count is at {@code offset}; returns the offset after them. */
  private static int skipAttributes(ClassReader reader, int offset) {
    int attributes = reader.readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < attributes; ++i) {
      offset += 6 + reader.readInt(offset + 2); // name, length, info
    }
    return offset;
  }
}
