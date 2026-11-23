package datadog.trace.agent.tooling.bytebuddy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.muzzle.Reference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;

class FieldAccessorsTest {

  /** The accessor interface, visible to the redefined class through the test's loader. */
  public interface SampleFields {
    String $$DD$$__name();

    int $$DD$$__count();

    long $$DD$$__total();

    byte[] $$DD$$__bytes();
  }

  /** A class with private fields, as a library class would have. */
  public static class Sample {
    private final String name;
    private final int count;
    private long total;
    private byte[] bytes;
    private static String shared = "static";

    public Sample(String name, int count, long total, byte[] bytes) {
      this.name = name;
      this.count = count;
      this.total = total;
      this.bytes = bytes;
    }

    @Override
    public String toString() {
      return name + count + total + bytes.length + shared;
    }
  }

  private static final String[] SAMPLE_GETTERS = {
    "$$DD$$__name", "name",
    "$$DD$$__count", "count",
    "$$DD$$__total", "total",
    "$$DD$$__bytes", "bytes",
  };

  /** {@code Sample} redefined with {@code accessors}, in a child-first loader of its own. */
  private static Class<?> redefine(FieldAccessors accessors) {
    return new ByteBuddy()
        .redefine(Sample.class)
        .visit(accessors)
        .make()
        .load(FieldAccessorsTest.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
        .getLoaded();
  }

  private static Object newSample(Class<?> sample) throws ReflectiveOperationException {
    return sample
        .getConstructor(String.class, int.class, long.class, byte[].class)
        .newInstance("n", 7, 42L, new byte[] {1, 2});
  }

  @Test
  void gettersReturnTheFields() throws ReflectiveOperationException {
    Class<?> sample = redefine(new FieldAccessors(SampleFields.class, SAMPLE_GETTERS));
    Object instance = newSample(sample);

    SampleFields fields = assertInstanceOf(SampleFields.class, instance);
    assertEquals("n", fields.$$DD$$__name());
    assertEquals(7, fields.$$DD$$__count());
    assertEquals(42L, fields.$$DD$$__total());
    assertArrayEquals(new byte[] {1, 2}, fields.$$DD$$__bytes());
    // the class still works as before
    assertEquals("n7422static", instance.toString());
    // synthetic, so reflection-based tooling passes over the getters
    assertTrue(sample.getMethod("$$DD$$__name").isSynthetic());
  }

  /** An accessor interface whose field doesn't exist on {@code Sample}. */
  public interface MissingFields {
    String $$DD$$__name();

    Object $$DD$$__missing();
  }

  @Test
  void aMissingFieldLeavesTheClassAlone() throws ReflectiveOperationException {
    FieldAccessors accessors =
        new FieldAccessors(
            MissingFields.class, "$$DD$$__name", "name", "$$DD$$__missing", "missing");
    assertFalse(accessors.appliesTo(TypeDescription.ForLoadedType.of(Sample.class)));
    assertFalse(newSample(redefine(accessors)) instanceof MissingFields);
  }

  /** An accessor interface whose getter type doesn't match the field's. */
  public interface WrongType {
    Object $$DD$$__name();
  }

  @Test
  void aTypeMismatchLeavesTheClassAlone() throws ReflectiveOperationException {
    FieldAccessors accessors = new FieldAccessors(WrongType.class, "$$DD$$__name", "name");
    assertFalse(accessors.appliesTo(TypeDescription.ForLoadedType.of(Sample.class)));
    assertFalse(newSample(redefine(accessors)) instanceof WrongType);
  }

  /** An accessor interface reading a static field. */
  public interface StaticField {
    String $$DD$$__shared();
  }

  @Test
  void aStaticFieldLeavesTheClassAlone() {
    FieldAccessors accessors = new FieldAccessors(StaticField.class, "$$DD$$__shared", "shared");
    assertFalse(accessors.appliesTo(TypeDescription.ForLoadedType.of(Sample.class)));
  }

  @Test
  void appliesToAMatchingClass() {
    assertTrue(
        new FieldAccessors(SampleFields.class, SAMPLE_GETTERS)
            .appliesTo(TypeDescription.ForLoadedType.of(Sample.class)));
  }

  /** An accessor interface with an abstract method no field backs. */
  public interface Unbacked {
    String $$DD$$__name();

    String other();
  }

  @Test
  void rejectsBadDeclarations() {
    assertThrows(IllegalArgumentException.class, () -> new FieldAccessors(Sample.class, "a", "b"));
    assertThrows(IllegalArgumentException.class, () -> new FieldAccessors(SampleFields.class, "a"));
    assertThrows(IllegalArgumentException.class, () -> new FieldAccessors(SampleFields.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldAccessors(SampleFields.class, "$$DD$$__nope", "name"));
    // every abstract method must be backed, or the class would be left incomplete
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldAccessors(Unbacked.class, "$$DD$$__name", "name"));
    // the right number of pairs, but one getter twice and another never
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FieldAccessors(
                SampleFields.class,
                "$$DD$$__name",
                "name",
                "$$DD$$__name",
                "count",
                "$$DD$$__total",
                "total",
                "$$DD$$__count",
                "bytes"));
  }

  @Test
  void muzzleReferenceRequiresEachField() {
    Reference reference =
        new FieldAccessors(SampleFields.class, SAMPLE_GETTERS)
            .muzzleReference("com.example.Sample");
    assertEquals("com.example.Sample", reference.className);
    assertEquals(4, reference.fields.length);
    for (Reference.Field field : reference.fields) {
      assertTrue((field.flags & Reference.EXPECTS_NON_STATIC) != 0, field.name);
    }
  }

  @Test
  void nullFieldsReadAsNull() throws ReflectiveOperationException {
    Class<?> sample = redefine(new FieldAccessors(SampleFields.class, SAMPLE_GETTERS));
    Object instance =
        sample
            .getConstructor(String.class, int.class, long.class, byte[].class)
            .newInstance(null, 0, 0L, new byte[0]);
    assertNull(((SampleFields) instance).$$DD$$__name());
  }
}
