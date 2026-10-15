package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link KnownTagCodec}'s slot: bits [47-32] of an id, assigned by graph coloring. */
class KnownTagCodecSlotTest {
  @Test
  void everySlotIsBelowTheSlotCount() throws IllegalAccessException {
    for (Field field : KnownTags.class.getFields()) {
      if (field.getType() != long.class) {
        continue;
      }
      long tagId = field.getLong(null);
      if (KnownTagCodec.hasSlot(tagId)) {
        assertTrue(KnownTagCodec.slotOf(tagId) < KnownTagCodec.slotCount(), field.getName());
      } else {
        assertEquals(KnownTagCodec.NO_SLOT, KnownTagCodec.slotOf(tagId), field.getName());
      }
    }
  }

  @Test
  void tagsOnAnHttpServerSpanHaveDistinctSlots() {
    long[] httpServerTags = {
      KnownTags.SERVICE_ID,
      KnownTags.COMPONENT_ID,
      KnownTags.SPAN_KIND_ID,
      KnownTags.HTTP_METHOD_ID,
      KnownTags.HTTP_URL_ID,
      KnownTags.HTTP_STATUS_CODE_ID,
      KnownTags.HTTP_ROUTE_ID,
      KnownTags.HTTP_HOSTNAME_ID,
    };
    Set<Integer> slots = new HashSet<>();
    for (long tagId : httpServerTags) {
      assertTrue(KnownTagCodec.hasSlot(tagId), Long.toHexString(tagId));
      assertTrue(slots.add(KnownTagCodec.slotOf(tagId)), Long.toHexString(tagId));
    }
  }

  @Test
  void anOptionalTagOnASpanTypeHasASlot() {
    assertTrue(KnownTagCodec.hasSlot(KnownTags.PEER_PORT_ID));
    assertTrue(KnownTagCodec.hasSlot(KnownTags.DB_POOL_NAME_ID));
  }

  @Test
  void aTagOnNoSpanTypeHasNoSlot() {
    // the tracer overlay's routing-only keys appear on no modeled span type
    assertFalse(KnownTagCodec.hasSlot(KnownTags.RESOURCE_NAME_ID));
  }

  @Test
  void theSlotDoesNotChangeTheSerialOrFlags() {
    long tagId = KnownTags.HTTP_METHOD_ID;

    assertTrue(KnownTagCodec.hasSlot(tagId));
    assertEquals(KnownTags.HTTP_METHOD_NAME, KnownTagCodec.nameOf(tagId));
    assertTrue(KnownTagCodec.isIntercepted(tagId));
    assertFalse(KnownTagCodec.isTraceLevel(tagId));
  }

  @Test
  void aCustomTagHasNoSlot() {
    // keyOf gives a custom tag the id 0L, whose slot bits decode as slot 0
    assertEquals(0L, KnownTagCodec.keyOf("my.custom.tag"));
    assertFalse(KnownTagCodec.hasSlot(0L));
  }

  @Test
  void anUnslottedTagSharesTheReservedTopBit() {
    // An occupancy bit of 1L << slotOf(id) needs no branch for NO_SLOT: a long shift uses only the
    // low 6 bits of its count, so 0xFFFF lands on bit 63, which no real slot uses
    assertEquals(1L << 63, 1L << KnownTagCodec.slotOf(KnownTags.RESOURCE_NAME_ID));
    assertTrue(KnownTagCodec.slotCount() <= 63, "real slots must stay below the shared bit 63");
  }
}
