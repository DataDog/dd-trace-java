package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class TagMapLedgerTest {
  static final int SIZE = 32;

  @Test
  public void inOrder() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < SIZE; ++i) {
      ledger.set(key(i), value(i));
    }

    assertEquals(SIZE, ledger.estimateSize());

    int i = 0;
    for (TagMap.EntryChange entryChange : ledger) {
      TagMap.Entry entry = (TagMap.Entry) entryChange;

      assertEquals(key(i), entry.tag());
      assertEquals(value(i), entry.stringValue());

      ++i;
    }
  }

  @Test
  public void testTypes() {
    TagMap.Ledger ledger = TagMap.ledger();
    ledger.set("bool", true);
    ledger.set("int", 1);
    ledger.set("long", 1L);
    ledger.set("float", 1F);
    ledger.set("double", 1D);
    ledger.set("object", (Object) "string");
    ledger.set("string", "string");

    assertEntryRawType(TagMap.Entry.BOOLEAN, ledger, "bool");
    assertEntryRawType(TagMap.Entry.INT, ledger, "int");
    assertEntryRawType(TagMap.Entry.LONG, ledger, "long");
    assertEntryRawType(TagMap.Entry.FLOAT, ledger, "float");
    assertEntryRawType(TagMap.Entry.DOUBLE, ledger, "double");
    assertEntryRawType(TagMap.Entry.ANY, ledger, "object");
    assertEntryRawType(TagMap.Entry.OBJECT, ledger, "string");
  }

  @Test
  public void buildMutable() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < SIZE; ++i) {
      ledger.set(key(i), value(i));
    }

    assertEquals(SIZE, ledger.estimateSize());

    TagMap map = ledger.build();
    for (int i = 0; i < SIZE; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
    assertEquals(SIZE, map.size());

    // just proving that the map is mutable
    map.set(key(1000), value(1000));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void buildMutable(TagMapType mapType) {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < SIZE; ++i) {
      ledger.set(key(i), value(i));
    }

    assertEquals(SIZE, ledger.estimateSize());

    TagMap map = ledger.build(mapType.factory);
    for (int i = 0; i < SIZE; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
    assertEquals(SIZE, map.size());

    // just proving that the map is mutable
    map.set(key(1000), value(1000));
  }

  @Test
  public void buildImmutable() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < SIZE; ++i) {
      ledger.set(key(i), value(i));
    }

    assertEquals(SIZE, ledger.estimateSize());

    TagMap map = ledger.buildImmutable();
    for (int i = 0; i < SIZE; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
    assertEquals(SIZE, map.size());

    assertFrozen(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void buildImmutable(TagMapType mapType) {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < SIZE; ++i) {
      ledger.set(key(i), value(i));
    }

    assertEquals(SIZE, ledger.estimateSize());

    TagMap map = ledger.buildImmutable(mapType.factory);
    for (int i = 0; i < SIZE; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
    assertEquals(SIZE, map.size());

    assertFrozen(map);
  }

  @Test
  public void build_empty() {
    TagMap.Ledger ledger = TagMap.ledger();
    assertTrue(ledger.isDefinitelyEmpty());
    assertNotSame(TagMap.EMPTY, ledger.build());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void build_empty(TagMapType mapType) {
    TagMap.Ledger ledger = TagMap.ledger();
    assertTrue(ledger.isDefinitelyEmpty());
    assertNotSame(mapType.empty(), ledger.build(mapType.factory));
  }

  @Test
  public void buildImmutable_empty() {
    TagMap.Ledger ledger = TagMap.ledger();
    assertTrue(ledger.isDefinitelyEmpty());
    assertSame(TagMap.EMPTY, ledger.buildImmutable());
  }

  @Test
  public void isDefinitelyEmpty_emptyMap() {
    TagMap.Ledger ledger = TagMap.ledger();
    ledger.set("foo", "bar");
    ledger.remove("foo");

    assertFalse(ledger.isDefinitelyEmpty());
    TagMap map = ledger.build();
    assertTrue(map.isEmpty());
  }

  @Test
  public void builderExpansion() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < 100; ++i) {
      ledger.set(key(i), value(i));
    }

    TagMap map = ledger.build();
    for (int i = 0; i < 100; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
  }

  @Test
  public void builderPresized() {
    TagMap.Ledger ledger = TagMap.ledger(100);
    for (int i = 0; i < 100; ++i) {
      ledger.set(key(i), value(i));
    }

    TagMap map = ledger.build();
    for (int i = 0; i < 100; ++i) {
      assertEquals(value(i), map.getString(key(i)));
    }
  }

  @Test
  public void buildWithRemoves() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < SIZE; ++i) {
      ledger.set(key(i), value(i));
    }

    for (int i = 0; i < SIZE; i += 2) {
      ledger.remove(key(i));
    }

    TagMap map = ledger.build();
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
    TagMap.Ledger ledger = TagMap.ledger();
    ledger.set("foo", "bar");
    ledger.smartRemove("foo");

    assertTrue(ledger.containsRemovals());
  }

  @Test
  public void smartRemoval_missingCase() {
    TagMap.Ledger ledger = TagMap.ledger();
    ledger.smartRemove("foo");

    assertFalse(ledger.containsRemovals());
  }

  @Test
  public void reset() {
    TagMap.Ledger ledger = TagMap.ledger(2);

    ledger.set(key(0), value(0));
    TagMap map0 = ledger.build();

    ledger.reset();

    ledger.set(key(1), value(1));
    TagMap map1 = ledger.build();

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

  static final void assertEntryRawType(byte expectedType, TagMap.Ledger ledger, String tag) {
    TagMap.Entry entry = ledger.findLastEntry(tag);
    assertEquals(expectedType, entry.rawType);
  }

  static final void assertFrozen(TagMap map) {
    IllegalStateException ex = null;
    try {
      map.set("foo", "bar");
    } catch (IllegalStateException e) {
      ex = e;
    }
    assertNotNull(ex);
  }
}
