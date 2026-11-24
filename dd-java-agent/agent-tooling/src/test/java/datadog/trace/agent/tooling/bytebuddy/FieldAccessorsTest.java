package datadog.trace.agent.tooling.bytebuddy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.muzzle.Reference;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.Arrays;
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
    return redefine(Sample.class, accessors);
  }

  /** {@code type} redefined with {@code accessors}, in a child-first loader of its own. */
  private static Class<?> redefine(Class<?> type, FieldAccessors accessors) {
    return new ByteBuddy()
        .redefine(type)
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

  /** One getter, for the classes below that have just a {@code name} field. */
  public interface NameField {
    String $$DD$$__name();
  }

  public interface WithOverload {
    String $$DD$$__name();

    String $$DD$$__name(int i);
  }

  public interface ObjectName {
    Object $$DD$$__name();
  }

  /** Redeclares the inherited getter covariantly; an interface override gets no bridge. */
  public interface CovariantName extends ObjectName {
    @Override
    String $$DD$$__name();
  }

  @Test
  void rejectsAnOverloadThatWouldBeLeftUnimplemented() {
    // only the no-argument method is generated, so the overload would throw AbstractMethodError
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldAccessors(WithOverload.class, "$$DD$$__name", "name"));
  }

  @Test
  void aCovariantRedeclarationWorksThroughBothDeclarations() throws ReflectiveOperationException {
    // javac bridges the inherited Object-returning declaration with a default method
    Class<?> sample = redefine(new FieldAccessors(CovariantName.class, "$$DD$$__name", "name"));
    Object instance = newSample(sample);

    assertEquals("n", ((CovariantName) instance).$$DD$$__name());
    assertEquals("n", ((ObjectName) instance).$$DD$$__name());
  }

  /** Declares no serialVersionUID, so its default value depends on its interfaces and methods. */
  public static class SerializableSample implements Serializable {
    private final String name;

    public SerializableSample(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }
  }

  @Test
  void keepsASerializableClassesDefaultSerialVersionUID() throws ReflectiveOperationException {
    long original = ObjectStreamClass.lookup(SerializableSample.class).getSerialVersionUID();
    Class<?> redefined =
        redefine(
            SerializableSample.class, new FieldAccessors(NameField.class, "$$DD$$__name", "name"));

    assertInstanceOf(NameField.class, redefined.getConstructor(String.class).newInstance("n"));
    assertEquals(original, ObjectStreamClass.lookup(redefined).getSerialVersionUID());
  }

  /** A generic class, whose signature attribute must name the added interface too. */
  public static class GenericSample<T> {
    private final String name;
    private final T value;

    public GenericSample(String name, T value) {
      this.name = name;
      this.value = value;
    }

    public T value() {
      return value;
    }
  }

  @Test
  void addsTheInterfaceToAGenericClassesSignature() throws ReflectiveOperationException {
    Class<?> redefined =
        redefine(GenericSample.class, new FieldAccessors(NameField.class, "$$DD$$__name", "name"));

    assertTrue(Arrays.asList(redefined.getGenericInterfaces()).contains(NameField.class));
    Object instance = redefined.getConstructor(String.class, Object.class).newInstance("n", 1);
    assertEquals("n", ((NameField) instance).$$DD$$__name());
  }

  /** Already implements the accessor interface itself. */
  public static class AlreadyImplements implements NameField {
    private final String name;

    public AlreadyImplements(String name) {
      this.name = name;
    }

    @Override
    public String $$DD$$__name() {
      return "own " + name;
    }
  }

  @Test
  void leavesAClassThatAlreadyImplementsTheInterfaceAlone() throws ReflectiveOperationException {
    Class<?> redefined =
        redefine(
            AlreadyImplements.class, new FieldAccessors(NameField.class, "$$DD$$__name", "name"));

    Object instance = redefined.getConstructor(String.class).newInstance("n");
    assertEquals("own n", ((NameField) instance).$$DD$$__name());
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
