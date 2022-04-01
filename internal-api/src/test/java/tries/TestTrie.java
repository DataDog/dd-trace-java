package tries;

import datadog.trace.util.StringTrie;

// Generated from 'test.trie' - DO NOT EDIT!
public final class TestTrie {
  public static int apply(String key) {
    return TRIE.apply(key);
  }

  private static final String TRIE_DATA =
      "\003\117\143\146\001\002\013\001\074\u8001\002\056\160\u4007\010\036\002\124\146\003\004\007\001\157\u4002"
          + "\001\044\000\u800d\002\056\142\u4008\006\004\001\124\005\u8003\001\056\u4009\001\106\007\u8005\001"
          + "\056\u400a\001\146\004\002\056\142\u400b\006\004\001\106\011\u8004\001\056\u400c\001\123\012\u8006"
          + "\u800e";

  private static final String[] TRIE_SEGMENTS = {
    "ne", //
    "om", //
    "w", //
    "oo", //
    "hree", //
    "ar", //
    "ive", //
    "any", //
    "our", //
    "ix", //
    "oobar.Two$", //
  };

  private static final StringTrie TRIE = new StringTrie(TRIE_DATA, TRIE_SEGMENTS);

  private TestTrie() {}
}
