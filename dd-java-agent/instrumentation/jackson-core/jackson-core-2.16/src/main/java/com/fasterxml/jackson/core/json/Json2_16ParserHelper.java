package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer2_16Helper;

public final class Json2_16ParserHelper {
  private Json2_16ParserHelper() {}

  public static boolean fetchInterner(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer2_16Helper.fetchInterner(jsonParser._symbols);
  }
}
