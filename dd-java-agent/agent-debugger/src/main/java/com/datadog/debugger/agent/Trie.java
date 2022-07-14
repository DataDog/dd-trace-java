package com.datadog.debugger.agent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// TODO This naive implementation should be improved
// cf https://github.com/rohansuri/adaptive-radix-tree
// or Any PATRICIA derived algorithm

/**
 * There is 2 ways to use this Trie:
 *
 * <p>1. insert full strings and match an exact string or a prefix on them 2. insert prefix strings
 * and match a full string on them. (#hasMatchingPrefix) prefixMode indicates we have inserted
 * prefixes inside the trie (usage 2.)
 */
public class Trie {
  private TrieNode root;

  public Trie() {
    root = new TrieNode((char) 0);
  }

  public Trie(Collection<String> collection) {
    this();
    for (String str : collection) {
      insert(str);
    }
  }

  public void insert(String str) {
    Map<Character, TrieNode> children = root.children;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      TrieNode node;
      if (children.containsKey(c)) {
        node = children.get(c);
      } else {
        node = new TrieNode(c);
        children.put(c, node);
      }
      children = node.children;
      node.isLeaf = i == str.length() - 1;
      if (node.isLeaf) {
        node.str = str;
      }
    }
  }

  /** @return if the string is in the trie. */
  public boolean contains(String str) {
    TrieNode t = searchNode(str, false);
    return t != null && t.isLeaf;
  }

  /** @return if there is any word in the trie that starts with the given prefix */
  public boolean containsPrefix(String prefix) {
    return searchNode(prefix, false) != null;
  }

  /** @return true if str matches one of the prefixes stored into the trie */
  public boolean hasMatchingPrefix(String str) {
    return searchNode(str, true) != null;
  }

  /** @return true is there is no string inserted into the Trie, otherwise false */
  public boolean isEmpty() {
    return root.children.isEmpty();
  }

  /**
   * @param prefix prefix to search into the trie
   * @return the string if unique that matches the given prefix, otherwise null
   */
  public String getStringStartingWith(String prefix) {
    TrieNode node = searchNode(prefix, false);
    if (node == null) {
      return null;
    }
    // while there is a unique path to the leaf, move forward
    while (!node.isLeaf && node.children.size() == 1) {
      node = node.children.values().iterator().next();
    }
    return node.str;
  }

  /**
   * @param str String to search into the trie
   * @param prefixMode indicates String in the trie are prefixed and when reaching the leaf node we
   *     return it
   * @return last node that matches the whole given string or any prefix if prefixMode is true
   */
  private TrieNode searchNode(String str, boolean prefixMode) {
    Map<Character, TrieNode> children = root.children;
    TrieNode node = null;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (children.containsKey(c)) {
        node = children.get(c);
        children = node.children;
        if (prefixMode && node.isLeaf) {
          return node;
        }
      } else {
        return null;
      }
    }
    return node;
  }

  public static String reverseStr(String str) {
    if (str == null) {
      return null;
    }
    return new StringBuilder(str).reverse().toString();
  }

  private static class TrieNode {
    final char c;
    final Map<Character, TrieNode> children = new HashMap<>();
    boolean isLeaf;
    String str;

    public TrieNode(char c) {
      this.c = c;
    }
  }
}
