package datadog.trace.agent.tooling.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Muzzle references are generated from {@code installFieldAccessors}, with nothing declared. */
class FieldAccessorMuzzleReferencesTest {

  public interface SampleFields {
    String $$DD$$__name();

    int $$DD$$__count();

    long $$DD$$__total();

    byte[] $$DD$$__bytes();
  }

  static final class AccessorModule extends InstrumenterModule.Tracing
      implements Instrumenter.ForSingleType, Instrumenter.HasTypeAdvice {
    AccessorModule() {
      super("test-accessors");
    }

    @Override
    public String instrumentedType() {
      return "com.example.Sample";
    }

    @Override
    public void typeAdvice(TypeTransformer transformer) {
      transformer.installFieldAccessors(
          SampleFields.class,
          "$$DD$$__name",
          "name",
          "$$DD$$__count",
          "count",
          "$$DD$$__total",
          "total",
          "$$DD$$__bytes",
          "bytes");
    }
  }

  @Test
  void installFieldAccessorsGeneratesFieldReferences() {
    List<Reference> references =
        MuzzleGenerationProcessor.fieldAccessorReferences(new AccessorModule());

    assertEquals(1, references.size());
    Reference reference = references.get(0);
    assertEquals("com.example.Sample", reference.className);
    Set<String> fields = new HashSet<>();
    for (Reference.Field field : reference.fields) {
      fields.add(field.name + ":" + field.fieldType);
      assertTrue((field.flags & Reference.EXPECTS_NON_STATIC) != 0, field.name);
    }
    Set<String> expected = new HashSet<>();
    expected.add("name:Ljava/lang/String;");
    expected.add("count:I");
    expected.add("total:J");
    expected.add("bytes:[B");
    assertEquals(expected, fields);
  }
}
