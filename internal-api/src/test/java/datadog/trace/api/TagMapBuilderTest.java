package datadog.trace.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TagMapBuilderTest {
  static final int SIZE = 32;

  @Test
  public void inOrder() {
    TagMap.Builder builder = TagMap.builder();
    for (int i = 0; i < SIZE; ++i) {
      builder.put(key(i), value(i));
    }

    assertEquals(SIZE, builder.estimateSize());

    int i = 0;
    for (TagMap.Entry entry : builder) {
      assertEquals(key(i), entry.tag());
      assertEquals(value(i), entry.stringValue());

      ++i;
    }
  }

  @Test
  public void testTypes() {
    TagMap.Builder builder = TagMap.builder();
    builder.put("bool", true);
    builder.put("int", 1);
    builder.put("long", 1L);
    builder.put("float", 1F);
    builder.put("double", 1D);
    builder.put("object", (Object) "string");
    builder.put("string", "string");

    assertEntryRawType(TagMap.Entry.BOOLEAN, builder, "bool");
    assertEntryRawType(TagMap.Entry.INT, builder, "int");
    assertEntryRawType(TagMap.Entry.LONG, builder, "long");
    assertEntryRawType(TagMap.Entry.FLOAT, builder, "float");
    assertEntryRawType(TagMap.Entry.DOUBLE, builder, "double");
    assertEntryRawType(TagMap.Entry.ANY, builder, "object");
    assertEntryRawType(TagMap.Entry.OBJECT, builder, "string");
  }

  @Test
  public void buildMutable() {
    TagMap.Builder builder = TagMap.builder();
    for (int i = 0; i < SIZE; ++i) {
      builder.put(key(i), value(i));
    }

    assertEquals(SIZE, builder.estimateSize());

    TagMap map = builder.build();
    for (int i = 0; i < SIZE; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
    assertEquals(SIZE, map.computeSize());

    // just proving that the map is mutable
    map.set(key(1000), value(1000));
  }

  @Test
  public void buildImmutable() {
    TagMap.Builder builder = TagMap.builder();
    for (int i = 0; i < SIZE; ++i) {
      builder.put(key(i), value(i));
    }

    assertEquals(SIZE, builder.estimateSize());

    TagMap map = builder.buildImmutable();
    for (int i = 0; i < SIZE; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
    assertEquals(SIZE, map.computeSize());

    assertFrozen(map);
  }

  @Test
  public void build_empty() {
    TagMap.Builder builder = TagMap.builder();
    assertTrue(builder.isDefinitelyEmpty());
    assertNotSame(TagMap.EMPTY, builder.build());
  }

  @Test
  public void buildImmutable_empty() {
    TagMap.Builder builder = TagMap.builder();
    assertTrue(builder.isDefinitelyEmpty());
    assertSame(TagMap.EMPTY, builder.buildImmutable());
  }

  @Test
  public void isDefinitelyEmpty_emptyMap() {
    TagMap.Builder builder = TagMap.builder();
    builder.put("foo", "bar");
    builder.remove("foo");

    assertFalse(builder.isDefinitelyEmpty());
    TagMap map = builder.build();
    assertTrue(map.checkIfEmpty());
  }

  @Test
  public void builderExpansion() {
    TagMap.Builder builder = TagMap.builder();
    for (int i = 0; i < 100; ++i) {
      builder.put(key(i), value(i));
    }

    TagMap map = builder.build();
    for (int i = 0; i < 100; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
  }

  @Test
  public void builderPresized() {
    TagMap.Builder builder = TagMap.builder(100);
    for (int i = 0; i < 100; ++i) {
      builder.put(key(i), value(i));
    }

    TagMap map = builder.build();
    for (int i = 0; i < 100; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
  }

  @Test
  public void buildWithRemoves() {
    TagMap.Builder builder = TagMap.builder();
    for (int i = 0; i < SIZE; ++i) {
      builder.put(key(i), value(i));
    }

    for (int i = 0; i < SIZE; i += 2) {
      builder.remove(key(i));
    }

    TagMap map = builder.build();
    for (int i = 0; i < SIZE; ++i) {
      if ((i % 2) == 0) {
        assertNull(map.getString(key(i)));
      } else {
        assertEquals(value(i), map.getString(key(i)));
      }
    }
  }

  @Test
  public void smartRemoval_existingCase() {
    TagMap.Builder builder = TagMap.builder();
    builder.put("foo", "bar");
    builder.smartRemove("foo");

    assertTrue(builder.containsRemovals());
  }

  @Test
  public void smartRemoval_missingCase() {
    TagMap.Builder builder = TagMap.builder();
    builder.smartRemove("foo");

    assertFalse(builder.containsRemovals());
  }

  @Test
  public void reset() {
    TagMap.Builder builder = TagMap.builder(2);

    builder.put(key(0), value(0));
    TagMap map0 = builder.build();

    builder.reset();

    builder.put(key(1), value(1));
    TagMap map1 = builder.build();

    assertEquals(value(0), map0.getString(key(0)));
    assertNull(map1.getString(key(0)));

    assertNull(map0.getString(key(1)));
    assertEquals(value(1), map1.getString(key(1)));
  }

  static final String key(int i) {
    return "key-" + i;
  }

  static final String value(int i) {
    return "value-" + i;
  }

  static final void assertEntryRawType(byte expectedType, TagMap.Builder builder, String tag) {
    TagMap.Entry entry = builder.findLastEntry(tag);
    assertEquals(expectedType, entry.rawType);
  }

  static final void assertFrozen(TagMap map) {
    IllegalStateException ex = null;
    try {
      map.put("foo", "bar");
    } catch (IllegalStateException e) {
      ex = e;
    }
    assertNotNull(ex);
  }
}
