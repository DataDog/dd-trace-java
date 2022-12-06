package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class TrieTest {

  @Test
  public void prefixMatching() {
    Trie trie = new Trie();
    trie.insert("foo");
    trie.insert("bar");
    assertTrue(trie.hasMatchingPrefix("foobar"));
    assertTrue(trie.hasMatchingPrefix("barfoo"));
    assertFalse(trie.hasMatchingPrefix("fobar"));
    assertFalse(trie.hasMatchingPrefix("bafoo"));
    assertFalse(trie.hasMatchingPrefix(""));
  }

  @Test
  public void startsWith() {
    Trie trie = new Trie();
    trie.insert("abcde");
    trie.insert("abcfe");
    assertTrue(trie.containsPrefix("a"));
    assertTrue(trie.containsPrefix("abc"));
    assertTrue(trie.containsPrefix("abcde"));
    assertTrue(trie.containsPrefix("abcfe"));
    assertFalse(trie.containsPrefix("sgsg"));
  }

  @Test
  public void getStringStartingWith() {
    Trie trie = new Trie();
    trie.insert("java.Main/debugger/datadog/com/java/main/src");
    trie.insert("java.Configuration/config/debugger/datadog/com/java/main/src");
    assertEquals(
        "java.Main/debugger/datadog/com/java/main/src",
        trie.getStringStartingWith("java.Main/debugger/datadog/com"));
  }

  @Test
  public void getStringStartingWithAmbiguous() {
    Trie trie = new Trie();
    trie.insert("abcde");
    trie.insert("abcfe");
    assertNull(trie.getStringStartingWith("abc"));
  }

  @Test
  public void empty() {
    Trie trie = new Trie();
    assertTrue(trie.isEmpty());
    trie.insert("");
    assertTrue(trie.isEmpty());
    trie.insert("abc");
    assertFalse(trie.isEmpty());
  }
}
