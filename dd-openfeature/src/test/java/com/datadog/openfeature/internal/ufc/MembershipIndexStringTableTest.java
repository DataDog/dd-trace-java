package com.datadog.openfeature.internal.ufc;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class MembershipIndexStringTableTest {
  private static final int NONE = Integer.MAX_VALUE;

  /**
   * "Aa" and "BB" have the same hash code, so every string made of them after the same prefix
   * collides. In a 16-slot table, these start at slot 12.
   */
  private static final String[] COLLIDING = {
    "aAaAaAa", "aAaAaBB", "aAaBBAa", "aAaBBBB", "aBBAaAa", "aBBAaBB", "aBBBBAa", "aBBBBBB"
  };

  @Test
  void findsEveryKeyOfAFullCollisionChain() {
    // Every key starts at slot 12, so the chain fills half the table and wraps past its end.
    final MembershipIndex.StringTable table = table(true, COLLIDING);

    assertEquals(16, table.capacity());
    for (int i = 0; i < COLLIDING.length; i++) {
      assertEquals(i, table.get(COLLIDING[i]), COLLIDING[i]);
    }
  }

  @Test
  void missesAKeyThatCollidesWithEveryKey() {
    final MembershipIndex.StringTable table = table(true, "aAaAaAa", "aBBBBBB");

    assertEquals(NONE, table.get("aAaBBAa"));
    assertEquals(NONE, table.get("aBBAaBB"));
  }

  @Test
  void separatesTheKeysWhoseHashCodeIsZero() {
    // The empty string and "\0" both hash to 0, which the table keeps for empty slots.
    final MembershipIndex.StringTable table = table(true, "", "\0");

    assertEquals(0, table.get(""));
    assertEquals(1, table.get("\0"));
    assertEquals(NONE, table.get("\0\0"));
  }

  @Test
  void keepsTheFirstIndexOfARepeatedKey() {
    final MembershipIndex.StringTable table = table(true, "a", "b", "a", "b");

    assertEquals(0, table.get("a"));
    assertEquals(1, table.get("b"));
  }

  @Test
  void keepsNoIndexesForAListWithoutNumbers() {
    final MembershipIndex.StringTable table = table(false, "a", "b", "c");

    assertEquals(0, table.get("a"));
    assertEquals(0, table.get("c"));
    assertEquals(NONE, table.get("d"));
  }

  @Test
  void findsNothingInAnEmptyTable() {
    final MembershipIndex.StringTable table = table(true);

    assertEquals(NONE, table.get(""));
    assertEquals(NONE, table.get("a"));
  }

  @TableTest({
    "scenario                              | attribute | result",
    "string before the first number        | a         | 1     ",
    "string after the first number         | b         | -3    ",
    "numeric string after the first number | 2         | 1     ",
    "numeric string matching nothing       | 3         | 0     ",
    "string in no list position            | z         | -3    "
  })
  void decidesAMixedListInListOrder(final String attribute, final int result) {
    // The scan matches "a" before it reaches 1.0, and throws at 1.0 for any other non-number.
    final MembershipIndex index = MembershipIndex.build(asList("a", 1.0, "b", 2.0));

    assertEquals(result, index.lookup(attribute));
  }

  @TableTest({
    "scenario               | attribute | result",
    "string in the list     | b         | 1     ",
    "string not in the list | z         | 0     ",
    "string of a boolean    | true      | 1     "
  })
  void decidesAListWithoutNumbers(final String attribute, final int result) {
    final MembershipIndex index = MembershipIndex.build(asList("a", "b", true));

    assertEquals(result, index.lookup(attribute));
  }

  @Test
  void decidesAListWithoutStrings() {
    final MembershipIndex index = MembershipIndex.build(asList(1.0, 2.0));

    assertEquals(MembershipIndex.MATCH, index.lookup("2"));
    assertEquals(MembershipIndex.NO_MATCH, index.lookup("3"));
    assertEquals(0, MembershipIndex.throwsAt(index.lookup("a")));
  }

  @Test
  void buildsNoIndexForAnEmptyList() {
    final List<Object> empty = asList();

    assertNull(MembershipIndex.build(empty));
  }

  private static MembershipIndex.StringTable table(final boolean withValues, final String... keys) {
    final int[] values = new int[keys.length];
    for (int i = 0; i < keys.length; i++) {
      values[i] = i;
    }
    return MembershipIndex.StringTable.of(keys, values, keys.length, withValues);
  }
}
