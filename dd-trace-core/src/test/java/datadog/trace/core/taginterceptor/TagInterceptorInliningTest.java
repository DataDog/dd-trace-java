package datadog.trace.core.taginterceptor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

/**
 * Pins code shapes that C2's inlining depends on and no behavioral test can see.
 *
 * <p>A tag set with a constant id should inline through to its {@link InterceptHandler}: C2 inlines
 * {@code interceptTag(DDSpanContext, long, Object)}, then {@link InterceptHandler#forSerial}, whose
 * constant serial leaves one arm, so the handler is a constant and its call binds statically. C2
 * decides by bytecode size before it folds anything, so both must stay small:
 *
 * <ul>
 *   <li>{@code interceptTag} within {@code MaxInlineSize}, 35 bytes by default, so it inlines even
 *       at a call site with no profile.
 *   <li>{@code forSerial} within {@code FreqInlineSize}, 325 bytes by default, so it inlines at a
 *       hot call site. Its arms only return constants, so this holds as intercepted tags are added.
 * </ul>
 */
class TagInterceptorInliningTest {
  private static final int MAX_INLINE_SIZE = 35;
  private static final int FREQ_INLINE_SIZE = 325;

  @Test
  void interceptTagByIdIsSmallEnoughToAlwaysInline() throws IOException {
    int length =
        codeLength(
            TagInterceptor.class,
            "interceptTag",
            "(Ldatadog/trace/core/DDSpanContext;JLjava/lang/Object;)Z");

    assertTrue(
        length <= MAX_INLINE_SIZE,
        "interceptTag(span, long, value) is "
            + length
            + " bytes of bytecode, over MaxInlineSize; keep the routing in InterceptHandler (see"
            + " this test's Javadoc)");
  }

  @Test
  void forSerialIsSmallEnoughToInlineWhenHot() throws IOException {
    int length =
        codeLength(
            InterceptHandler.class,
            "forSerial",
            "(I)Ldatadog/trace/core/taginterceptor/InterceptHandler;");

    assertTrue(
        length <= FREQ_INLINE_SIZE,
        "InterceptHandler.forSerial is "
            + length
            + " bytes of bytecode, over FreqInlineSize; its arms should only return constants (see"
            + " this test's Javadoc)");
  }

  /** The {@code code_length} of a method's Code attribute, read from the class file. */
  private static int codeLength(Class<?> type, String name, String descriptor) throws IOException {
    ClassReader reader;
    try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
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
