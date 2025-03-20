package datadog.trace.api;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import datadog.trace.api.TagMap.BucketGroup;

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
  
  static void assertContainsDirectly(TagMap.Entry entry, TagMap.BucketGroup group) {
	int hash = entry.hash();
	String tag = entry.tag();
	
	assertSame(entry, group._find(hash, tag));
	
	assertSame(entry, group.findInChain(hash, tag));
	assertSame(group, group.findContainingGroupInChain(hash, tag));	
  }
  
  static void assertDoesntContainDirectly(TagMap.Entry entry, TagMap.BucketGroup group) {
    for ( int i = 0; i < BucketGroup.LEN; ++i ) {
      assertNotSame(entry, group._entryAt(i));
    }
  }
}
