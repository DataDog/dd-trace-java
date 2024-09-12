package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer2_12Helper;

public final class Json2_12ParserHelper {
  private Json2_12ParserHelper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer2_12Helper.fetchIntern(jsonParser._symbols);
  }
}
