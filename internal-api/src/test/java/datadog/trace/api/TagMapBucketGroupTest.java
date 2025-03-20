package datadog.trace.api;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TagMapBucketGroupTest {
  @Test
  public void newGroup() {
	TagMap.Entry firstEntry = TagMap.Entry.newIntEntry("foo", 0xDD06);
	TagMap.Entry secondEntry = TagMap.Entry.newObjectEntry("bar", "quux");
	
	int firstHash = firstEntry.hash();
	int secondHash = secondEntry.hash();
	
	TagMap.BucketGroup group = new TagMap.BucketGroup(
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
	
	TagMap.BucketGroup group = new TagMap.BucketGroup(firstHash, firstEntry, secondHash, secondEntry);
	
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
		
	TagMap.BucketGroup group = new TagMap.BucketGroup(origHash, origEntry, otherHash, otherEntry);
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
	
	TagMap.BucketGroup group = new TagMap.BucketGroup(
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
	TagMap.BucketGroup firstGroup = fullGroup(startingIndex);
	
	for ( int offset = 0; offset < TagMap.BucketGroup.LEN; ++offset ) {
	  assertChainContainsTag(tag(startingIndex + offset), firstGroup);
	}
	
	TagMap.Entry newEntry = TagMap.Entry.newObjectEntry("new", "new");
	int newHash = newEntry.hash();
	
	// This is a test of the process used by TagMap#put
	assertNull(firstGroup._replace(newHash, newEntry));
	assertFalse(firstGroup._insert(newHash, newEntry));
	assertDoesntContainDirectly(newEntry, firstGroup);
	
	TagMap.BucketGroup newHeadGroup = new TagMap.BucketGroup(newHash, newEntry, firstGroup);
	assertContainsDirectly(newEntry, newHeadGroup);
	assertSame(firstGroup, newHeadGroup.prev);
	
	assertChainContainsTag("new", newHeadGroup);
	for ( int offset = 0; offset < TagMap.BucketGroup.LEN; ++offset ) {
	  assertChainContainsTag(tag(startingIndex + offset), newHeadGroup);
	}
  }
  
  @Test
  public void removeInChain() {
	TagMap.BucketGroup firstGroup = fullGroup(10);
	TagMap.BucketGroup headGroup = fullGroup(20, firstGroup);
	
	for ( int offset = 0; offset < TagMap.BucketGroup.LEN; ++offset ) {
	  assertChainContainsTag(tag(10, offset), headGroup);
	  assertChainContainsTag(tag(20, offset), headGroup);
	}
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2);

	String firstRemovedTag = tag(10, 1);
	int firstRemovedHash = TagMap._hash(firstRemovedTag);
	
	TagMap.BucketGroup firstContainingGroup = headGroup.findContainingGroupInChain(firstRemovedHash, firstRemovedTag);
	assertSame(firstContainingGroup, firstGroup);
	assertNotNull(firstContainingGroup._remove(firstRemovedHash, firstRemovedTag));
	
	assertChainDoesntContainTag(firstRemovedTag, headGroup);
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2 - 1);
	
	String secondRemovedTag = tag(20, 2);
	int secondRemovedHash = TagMap._hash(secondRemovedTag);
	
	TagMap.BucketGroup secondContainingGroup = headGroup.findContainingGroupInChain(secondRemovedHash, secondRemovedTag);
	assertSame(secondContainingGroup, headGroup);
	assertNotNull(secondContainingGroup._remove(secondRemovedHash, secondRemovedTag));
	
	assertChainDoesntContainTag(secondRemovedTag, headGroup);
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2 - 2);
  }
  
  @Test
  public void replaceInChain() {
	TagMap.BucketGroup firstGroup = fullGroup(10);
	TagMap.BucketGroup headGroup = fullGroup(20, firstGroup);
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2);
	
	TagMap.Entry firstReplacementEntry = TagMap.Entry.newObjectEntry(tag(10, 1), "replaced");
	assertNotNull(headGroup.replaceInChain(firstReplacementEntry.hash(), firstReplacementEntry));
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2);
	
	TagMap.Entry secondReplacementEntry = TagMap.Entry.newObjectEntry(tag(20, 2), "replaced");
	assertNotNull(headGroup.replaceInChain(secondReplacementEntry.hash(), secondReplacementEntry));
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2);
  }
  
  @Test
  public void insertInChain() {
	// set-up a chain with some gaps in it
	TagMap.BucketGroup firstGroup = fullGroup(10);
	TagMap.BucketGroup headGroup = fullGroup(20, firstGroup);
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2);
	
	String firstHoleTag = tag(10, 1);
	int firstHoleHash = TagMap._hash(firstHoleTag);
	firstGroup._remove(firstHoleHash, firstHoleTag);
	
	String secondHoleTag = tag(20, 2);
	int secondHoleHash = TagMap._hash(secondHoleTag);
	headGroup._remove(secondHoleHash, secondHoleTag);
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2 - 2);
	
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
	
	assertEquals(headGroup.sizeInChain(), TagMap.BucketGroup.LEN * 2);
  }
  
  @Test
  public void cloneChain() {
	TagMap.BucketGroup firstGroup = fullGroup(10);
	TagMap.BucketGroup secondGroup = fullGroup(20, firstGroup);
	TagMap.BucketGroup headGroup = fullGroup(30, secondGroup);
	
	TagMap.BucketGroup clonedHeadGroup = headGroup.cloneChain();
	TagMap.BucketGroup clonedSecondGroup = clonedHeadGroup.prev;
	TagMap.BucketGroup clonedFirstGroup = clonedSecondGroup.prev;
	
	assertGroupContentsStrictEquals(headGroup, clonedHeadGroup);
	assertGroupContentsStrictEquals(secondGroup, clonedSecondGroup);
	assertGroupContentsStrictEquals(firstGroup, clonedFirstGroup);
  }
  
  @Test
  public void removeGroupInChain() {
	TagMap.BucketGroup tailGroup = fullGroup(10);
	TagMap.BucketGroup secondGroup = fullGroup(20, tailGroup);
	TagMap.BucketGroup thirdGroup = fullGroup(30, secondGroup);
	TagMap.BucketGroup fourthGroup = fullGroup(40, thirdGroup);
	TagMap.BucketGroup headGroup = fullGroup(50, fourthGroup);
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
  
  static final TagMap.BucketGroup fullGroup(int startingIndex) {
	TagMap.Entry firstEntry = TagMap.Entry.newObjectEntry(tag(startingIndex), value(startingIndex));
	TagMap.Entry secondEntry = TagMap.Entry.newObjectEntry(tag(startingIndex + 1), value(startingIndex + 1));
		
	TagMap.BucketGroup group = new TagMap.BucketGroup(firstEntry.hash(), firstEntry, secondEntry.hash(), secondEntry);
	for ( int offset = 2; offset < TagMap.BucketGroup.LEN; ++offset ) {
	  TagMap.Entry anotherEntry = TagMap.Entry.newObjectEntry(tag(startingIndex + offset), value(startingIndex + offset));
	  group._insert(anotherEntry.hash(), anotherEntry);
	}
	return group;
  }
  
  static final TagMap.BucketGroup fullGroup(int startingIndex, TagMap.BucketGroup prev) {
	TagMap.BucketGroup group = fullGroup(startingIndex);
    group.prev = prev;
    return group;
  }
  
  static final String tag(int startingIndex, int offset) {
	return tag(startingIndex + offset);
  }
  
  static final String tag(int i) {
	return "tag-" + i;
  }
  
  static final String value(int startingIndex, int offset) {
	return value(startingIndex + offset);
  }
  
  static final String value(int i) {
	return "value-i";
  }
  
  static void assertContainsDirectly(TagMap.Entry entry, TagMap.BucketGroup group) {
	int hash = entry.hash();
	String tag = entry.tag();
	
	assertSame(entry, group._find(hash, tag));
	
	assertSame(entry, group.findInChain(hash, tag));
	assertSame(group, group.findContainingGroupInChain(hash, tag));	
  }
  
  static void assertDoesntContainDirectly(TagMap.Entry entry, TagMap.BucketGroup group) {
    for ( int i = 0; i < TagMap.BucketGroup.LEN; ++i ) {
      assertNotSame(entry, group._entryAt(i));
    }
  }
  
  static void assertChainContainsTag(String tag, TagMap.BucketGroup group) {
	int hash = TagMap._hash(tag);
	assertNotNull(group.findInChain(hash, tag));
  }
  
  static void assertChainDoesntContainTag(String tag, TagMap.BucketGroup group) {
	int hash = TagMap._hash(tag);
	assertNull(group.findInChain(hash,  tag));
  }
  
  static void assertGroupContentsStrictEquals(TagMap.BucketGroup expected, TagMap.BucketGroup actual) {
	for ( int i = 0; i < TagMap.BucketGroup.LEN; ++i ) {
	  assertEquals(expected._hashAt(i), actual._hashAt(i));
	  assertSame(expected._entryAt(i), actual._entryAt(i));
	}
  }
  
  static void assertChain(TagMap.BucketGroup... chain) {
	TagMap.BucketGroup cur;
	int index;
	for ( cur = chain[0], index = 0; cur != null; cur = cur.prev, ++index ) {
	  assertSame(chain[index], cur);
	}
	assertEquals(chain.length, index);
  }
}
