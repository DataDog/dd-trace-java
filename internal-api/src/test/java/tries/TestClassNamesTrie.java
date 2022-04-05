package tries;

import datadog.trace.util.ClassNameTrie;

// Generated from 'test_class_names.trie' - DO NOT EDIT!
public final class TestClassNamesTrie {
  public static int apply(String key) {
    return TRIE.apply(key);
  }

  private static final String TRIE_DATA =
      "\003\117\143\146\002\002\012\003\127\156\145\u8001\157\155\002\056\160\u4007\003\051\002\124\146\001\002\007\167\001"
          + "\157\u4002\001\044\u800d\157\157\002\056\142\u4008\002\010\001\124\004\150\162\145\145\u8003\141\162\001\056"
          + "\u4009\001\106\003\151\166\145\u8005\141\156\171\001\056\u400a\001\146\002\157\157\002\056\142\u400b\002\007"
          + "\001\106\003\157\165\162\u8004\141\162\001\056\u400c\001\123\002\151\170\u8006\157\157\142\141\162\056\124\167"
          + "\157\044\u800e";

  private static final ClassNameTrie TRIE = ClassNameTrie.create(TRIE_DATA);

  private TestClassNamesTrie() {}
}
