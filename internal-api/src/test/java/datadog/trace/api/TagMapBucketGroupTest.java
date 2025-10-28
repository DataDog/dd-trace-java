package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TagMapBucketGroupTest {
  @Test
  public void newGroup() {
    TagMap.Entry firstEntry = TagMap.Entry.newIntEntry("foo", 0xDD06);
    TagMap.Entry secondEntry = TagMap.Entry.newObjectEntry("bar", "quux");

    int firstHash = firstEntry.hash();
    int secondHash = secondEntry.hash();

    OptimizedTagMap.BucketGroup group =
        new OptimizedTagMap.BucketGroup(
            firstHash, firstEntry,
            secondHash, secondEntry);

    assertEquals(firstHash, group._hashAt(0));
    assertEquals(firstEntry, group._entryAt(0));

    assertEquals(secondEntry.hash(), group._hashAt(1));
    assertEquals(secondEntry, group._entryAt(1));

    assertFalse(group._isEmpty());
    assertFalse(group.isEmptyChain());

    assertContainsDirectly(firstEntry, group);
    assertContainsDirectly(secondEntry, group);
  }

  @Test
  public void _insert() {
    TagMap.Entry firstEntry = TagMap.Entry.newIntEntry("foo", 0xDD06);
    TagMap.Entry secondEntry = TagMap.Entry.newObjectEntry("bar", "quux");

    int firstHash = firstEntry.hash();
    int secondHash = secondEntry.hash();

    OptimizedTagMap.BucketGroup group =
        new OptimizedTagMap.BucketGroup(firstHash, firstEntry, secondHash, secondEntry);

    TagMap.Entry newEntry = TagMap.Entry.newAnyEntry("baz", "lorem ipsum");
    int newHash = newEntry.hash();

    assertTrue(group._insert(newHash, newEntry));

    assertContainsDirectly(newEntry, group);
    assertContainsDirectly(firstEntry, group);
    assertContainsDirectly(secondEntry, group);

    TagMap.Entry newEntry2 = TagMap.Entry.newDoubleEntry("new", 3.1415926535D);
    int newHash2 = newEntry2.hash();

    assertTrue(group._insert(newHash2, newEntry2));

    assertContainsDirectly(newEntry2, group);
    assertContainsDirectly(newEntry, group);
    assertContainsDirectly(firstEntry, group);
    assertContainsDirectly(secondEntry, group);

    TagMap.Entry overflowEntry = TagMap.Entry.newDoubleEntry("overflow", 2.718281828D);
    int overflowHash = overflowEntry.hash();
    assertFalse(group._insert(overflowHash, overflowEntry));

    assertDoesntContainDirectly(overflowEntry, group);
  }

  @Test
  public void _replace() {
    TagMap.Entry origEntry = TagMap.Entry.newIntEntry("replaceable", 0xDD06);
    TagMap.Entry otherEntry = TagMap.Entry.newObjectEntry("bar", "quux");

    int origHash = origEntry.hash();
    int otherHash = otherEntry.hash();

    OptimizedTagMap.BucketGroup group =
        new OptimizedTagMap.BucketGroup(origHash, origEntry, otherHash, otherEntry);
    assertContainsDirectly(origEntry, group);
    assertContainsDirectly(otherEntry, group);

    TagMap.Entry replacementEntry = TagMap.Entry.newBooleanEntry("replaceable", true);
    int replacementHash = replacementEntry.hash();
    assertEquals(replacementHash, origHash);

    TagMap.Entry priorEntry = group._replace(origHash, replacementEntry);
    assertSame(priorEntry, origEntry);

    assertContainsDirectly(replacementEntry, group);
    assertDoesntContainDirectly(priorEntry, group);

    TagMap.Entry dneEntry = TagMap.Entry.newAnyEntry("dne", "not present");
    int dneHash = dneEntry.hash();

    assertNull(group._replace(dneHash, dneEntry));
    assertDoesntContainDirectly(dneEntry, group);
  }

  @Test
  public void _remove() {
    TagMap.Entry firstEntry = TagMap.Entry.newIntEntry("first", 0xDD06);
    TagMap.Entry secondEntry = TagMap.Entry.newObjectEntry("second", "quux");

    int firstHash = firstEntry.hash();
    int secondHash = secondEntry.hash();

    OptimizedTagMap.BucketGroup group =
        new OptimizedTagMap.BucketGroup(
            firstHash, firstEntry,
            secondHash, secondEntry);

    assertFalse(group._isEmpty());

    assertContainsDirectly(firstEntry, group);
    assertContainsDirectly(secondEntry, group);

    assertSame(firstEntry, group._remove(firstHash, "first"));

    assertDoesntContainDirectly(firstEntry, group);
    assertContainsDirectly(secondEntry, group);
    assertFalse(group._isEmpty());

    assertSame(secondEntry, group._remove(secondHash, "second"));
    assertDoesntContainDirectly(secondEntry, group);

    assertTrue(group._isEmpty());
  }

  @Test
  public void groupChaining() {
    int startingIndex = 10;
    OptimizedTagMap.BucketGroup firstGroup = fullGroup(startingIndex);

    for (int offset = 0; offset < OptimizedTagMap.BucketGroup.LEN; ++offset) {
      assertChainContainsTag(tag(startingIndex + offset), firstGroup);
    }

    TagMap.Entry newEntry = TagMap.Entry.newObjectEntry("new", "new");
    int newHash = newEntry.hash();

    // This is a test of the process used by TagMap#put
    assertNull(firstGroup._replace(newHash, newEntry));
    assertFalse(firstGroup._insert(newHash, newEntry));
    assertDoesntContainDirectly(newEntry, firstGroup);

    OptimizedTagMap.BucketGroup newHeadGroup =
        new OptimizedTagMap.BucketGroup(newHash, newEntry, firstGroup);
    assertContainsDirectly(newEntry, newHeadGroup);
    assertSame(firstGroup, newHeadGroup.prev);

    assertChainContainsTag("new", newHeadGroup);
    for (int offset = 0; offset < OptimizedTagMap.BucketGroup.LEN; ++offset) {
      assertChainContainsTag(tag(startingIndex + offset), newHeadGroup);
    }
  }

  @Test
  public void removeInChain() {
    OptimizedTagMap.BucketGroup firstGroup = fullGroup(10);
    OptimizedTagMap.BucketGroup headGroup = fullGroup(20, firstGroup);

    for (int offset = 0; offset < OptimizedTagMap.BucketGroup.LEN; ++offset) {
      assertChainContainsTag(tag(10, offset), headGroup);
      assertChainContainsTag(tag(20, offset), headGroup);
    }

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2, headGroup.sizeInChain());

    String firstRemovedTag = tag(10, 1);
    int firstRemovedHash = TagMap.Entry._hash(firstRemovedTag);

    OptimizedTagMap.BucketGroup firstContainingGroup =
        headGroup.findContainingGroupInChain(firstRemovedHash, firstRemovedTag);
    assertSame(firstContainingGroup, firstGroup);
    assertNotNull(firstContainingGroup._remove(firstRemovedHash, firstRemovedTag));

    assertChainDoesntContainTag(firstRemovedTag, headGroup);

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2 - 1, headGroup.sizeInChain());

    String secondRemovedTag = tag(20, 2);
    int secondRemovedHash = TagMap.Entry._hash(secondRemovedTag);

    OptimizedTagMap.BucketGroup secondContainingGroup =
        headGroup.findContainingGroupInChain(secondRemovedHash, secondRemovedTag);
    assertSame(secondContainingGroup, headGroup);
    assertNotNull(secondContainingGroup._remove(secondRemovedHash, secondRemovedTag));

    assertChainDoesntContainTag(secondRemovedTag, headGroup);

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2 - 2, headGroup.sizeInChain());
  }

  @Test
  public void replaceInChain() {
    OptimizedTagMap.BucketGroup firstGroup = fullGroup(10);
    OptimizedTagMap.BucketGroup headGroup = fullGroup(20, firstGroup);

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2, headGroup.sizeInChain());

    TagMap.Entry firstReplacementEntry = TagMap.Entry.newObjectEntry(tag(10, 1), "replaced");
    assertNotNull(headGroup.replaceInChain(firstReplacementEntry.hash(), firstReplacementEntry));

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2, headGroup.sizeInChain());

    TagMap.Entry secondReplacementEntry = TagMap.Entry.newObjectEntry(tag(20, 2), "replaced");
    assertNotNull(headGroup.replaceInChain(secondReplacementEntry.hash(), secondReplacementEntry));

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2, headGroup.sizeInChain());
  }

  @Test
  public void insertInChain() {
    // set-up a chain with some gaps in it
    OptimizedTagMap.BucketGroup firstGroup = fullGroup(10);
    OptimizedTagMap.BucketGroup headGroup = fullGroup(20, firstGroup);

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2, headGroup.sizeInChain());

    String firstHoleTag = tag(10, 1);
    int firstHoleHash = TagMap.Entry._hash(firstHoleTag);
    firstGroup._remove(firstHoleHash, firstHoleTag);

    String secondHoleTag = tag(20, 2);
    int secondHoleHash = TagMap.Entry._hash(secondHoleTag);
    headGroup._remove(secondHoleHash, secondHoleTag);

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2 - 2, headGroup.sizeInChain());

    String firstNewTag = "new-tag-0";
    TagMap.Entry firstNewEntry = TagMap.Entry.newObjectEntry(firstNewTag, "new");
    int firstNewHash = firstNewEntry.hash();

    assertTrue(headGroup.insertInChain(firstNewHash, firstNewEntry));
    assertChainContainsTag(firstNewTag, headGroup);

    String secondNewTag = "new-tag-1";
    TagMap.Entry secondNewEntry = TagMap.Entry.newObjectEntry(secondNewTag, "new");
    int secondNewHash = secondNewEntry.hash();

    assertTrue(headGroup.insertInChain(secondNewHash, secondNewEntry));
    assertChainContainsTag(secondNewTag, headGroup);

    String thirdNewTag = "new-tag-2";
    TagMap.Entry thirdNewEntry = TagMap.Entry.newObjectEntry(secondNewTag, "new");
    int thirdNewHash = secondNewEntry.hash();

    assertFalse(headGroup.insertInChain(thirdNewHash, thirdNewEntry));
    assertChainDoesntContainTag(thirdNewTag, headGroup);

    assertEquals(OptimizedTagMap.BucketGroup.LEN * 2, headGroup.sizeInChain());
  }

  @Test
  public void cloneChain() {
    OptimizedTagMap.BucketGroup firstGroup = fullGroup(10);
    OptimizedTagMap.BucketGroup secondGroup = fullGroup(20, firstGroup);
    OptimizedTagMap.BucketGroup headGroup = fullGroup(30, secondGroup);

    OptimizedTagMap.BucketGroup clonedHeadGroup = headGroup.cloneChain();
    OptimizedTagMap.BucketGroup clonedSecondGroup = clonedHeadGroup.prev;
    OptimizedTagMap.BucketGroup clonedFirstGroup = clonedSecondGroup.prev;

    assertGroupContentsStrictEquals(headGroup, clonedHeadGroup);
    assertGroupContentsStrictEquals(secondGroup, clonedSecondGroup);
    assertGroupContentsStrictEquals(firstGroup, clonedFirstGroup);
  }

  @Test
  public void removeGroupInChain() {
    OptimizedTagMap.BucketGroup tailGroup = fullGroup(10);
    OptimizedTagMap.BucketGroup secondGroup = fullGroup(20, tailGroup);
    OptimizedTagMap.BucketGroup thirdGroup = fullGroup(30, secondGroup);
    OptimizedTagMap.BucketGroup fourthGroup = fullGroup(40, thirdGroup);
    OptimizedTagMap.BucketGroup headGroup = fullGroup(50, fourthGroup);
    assertChain(headGroup, fourthGroup, thirdGroup, secondGroup, tailGroup);

    // need to test group removal - at head, middle, and tail of the chain

    // middle
    assertSame(headGroup, headGroup.removeGroupInChain(thirdGroup));
    assertChain(headGroup, fourthGroup, secondGroup, tailGroup);

    // tail
    assertSame(headGroup, headGroup.removeGroupInChain(tailGroup));
    assertChain(headGroup, fourthGroup, secondGroup);

    // head
    assertSame(fourthGroup, headGroup.removeGroupInChain(headGroup));
    assertChain(fourthGroup, secondGroup);
  }

  static final OptimizedTagMap.BucketGroup fullGroup(int startingIndex) {
    TagMap.Entry firstEntry = TagMap.Entry.newObjectEntry(tag(startingIndex), value(startingIndex));
    TagMap.Entry secondEntry =
        TagMap.Entry.newObjectEntry(tag(startingIndex + 1), value(startingIndex + 1));

    OptimizedTagMap.BucketGroup group =
        new OptimizedTagMap.BucketGroup(
            firstEntry.hash(), firstEntry, secondEntry.hash(), secondEntry);
    for (int offset = 2; offset < OptimizedTagMap.BucketGroup.LEN; ++offset) {
      TagMap.Entry anotherEntry =
          TagMap.Entry.newObjectEntry(tag(startingIndex + offset), value(startingIndex + offset));
      group._insert(anotherEntry.hash(), anotherEntry);
    }
    return group;
  }

  static final OptimizedTagMap.BucketGroup fullGroup(
      int startingIndex, OptimizedTagMap.BucketGroup prev) {
    OptimizedTagMap.BucketGroup group = fullGroup(startingIndex);
    group.prev = prev;
    return group;
  }

  static final String tag(int startingIndex, int offset) {
    return tag(startingIndex + offset);
  }

  static final String tag(int i) {
    return "tag-" + i;
  }

  static final String value(int i) {
    return "value-" + i;
  }

  static void assertContainsDirectly(TagMap.Entry entry, OptimizedTagMap.BucketGroup group) {
    int hash = entry.hash();
    String tag = entry.tag();

    assertSame(entry, group._find(hash, tag));

    assertSame(entry, group.findInChain(hash, tag));
    assertSame(group, group.findContainingGroupInChain(hash, tag));
  }

  static void assertDoesntContainDirectly(TagMap.Entry entry, OptimizedTagMap.BucketGroup group) {
    for (int i = 0; i < OptimizedTagMap.BucketGroup.LEN; ++i) {
      assertNotSame(entry, group._entryAt(i));
    }
  }

  static void assertChainContainsTag(String tag, OptimizedTagMap.BucketGroup group) {
    int hash = TagMap.Entry._hash(tag);
    assertNotNull(group.findInChain(hash, tag));
  }

  static void assertChainDoesntContainTag(String tag, OptimizedTagMap.BucketGroup group) {
    int hash = TagMap.Entry._hash(tag);
    assertNull(group.findInChain(hash, tag));
  }

  static void assertGroupContentsStrictEquals(
      OptimizedTagMap.BucketGroup expected, OptimizedTagMap.BucketGroup actual) {
    for (int i = 0; i < OptimizedTagMap.BucketGroup.LEN; ++i) {
      assertEquals(expected._hashAt(i), actual._hashAt(i));
      assertSame(expected._entryAt(i), actual._entryAt(i));
    }
  }

  static void assertChain(OptimizedTagMap.BucketGroup... chain) {
    OptimizedTagMap.BucketGroup cur;
    int index;
    for (cur = chain[0], index = 0; cur != null; cur = cur.prev, ++index) {
      assertSame(chain[index], cur);
    }
    assertEquals(chain.length, index);
  }
}
