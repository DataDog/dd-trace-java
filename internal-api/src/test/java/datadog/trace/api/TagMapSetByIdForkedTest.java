package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the id-keyed {@code TagMap.set(long, ...)} family is observationally identical to the
 * name-keyed {@code set(String, ...)} family for stored known tags — the correctness guarantee the
 * id-path throughput win rests on. Forked because the resolver ({@link KnownTags#init}) is a global
 * static with no un-register (see {@code TagMapDenseForkedTest}).
 */
class TagMapSetByIdForkedTest {

  @BeforeAll
  static void registerResolver() {
    KnownTags.init();
    assertTrue(KnownTagCodec.isActive(), "resolver must be live for the dense store to engage");
  }

  private static TagMap map() {
    return (TagMap) TagMap.create();
  }

  /** For each stored id, set(long) and set(String name) land the same value under the same key. */
  @Test
  void objectValueMatchesNamePath() {
    long id = KnownTags.COMPONENT_ID;
    String name = KnownTagCodec.nameOf(id);
    assertTrue(KnownTagCodec.isStored(id));

    TagMap byId = map();
    byId.set(id, "spring-web");

    TagMap byName = map();
    byName.set(name, "spring-web");

    assertEquals("spring-web", byId.getString(name));
    assertEquals(byName.getString(name), byId.getString(name));
    assertEquals(byName.size(), byId.size());
    byId.checkIntegrity();
  }

  /** Typed primitive overloads box and round-trip exactly as the name-keyed typed setters do. */
  @Test
  void typedValuesMatchNamePath() {
    long portId = KnownTags.PEER_PORT_ID;
    String portName = KnownTagCodec.nameOf(portId);

    TagMap byId = map();
    byId.set(portId, 5432);

    TagMap byName = map();
    byName.set(portName, 5432);

    assertEquals(byName.getObject(portName), byId.getObject(portName));
    assertEquals(5432, ((Number) byId.getObject(portName)).intValue());
    byId.checkIntegrity();
  }

  /** Overwriting an id-set value in place returns the same state as the name path. */
  @Test
  void overwriteInPlace() {
    long id = KnownTags.DB_TYPE_ID;
    String name = KnownTagCodec.nameOf(id);

    TagMap map = map();
    map.set(id, "mysql");
    map.set(id, "postgresql");

    assertEquals("postgresql", map.getString(name));
    assertEquals(1, map.size());
    map.checkIntegrity();
  }

  /** A null Object value removes the tag, mirroring the name path's remove-on-null contract. */
  @Test
  void nullObjectRemoves() {
    long id = KnownTags.DB_INSTANCE_ID;
    String name = KnownTagCodec.nameOf(id);

    TagMap map = map();
    map.set(id, "petclinic");
    assertEquals("petclinic", map.getString(name));

    map.remove(name);
    assertNull(map.getObject(name));
    map.checkIntegrity();
  }
}
