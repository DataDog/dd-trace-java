package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer2_6Helper;

public final class Json2_6ParserHelper {
  private Json2_6ParserHelper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer2_6Helper.fetchIntern(jsonParser._symbols);
  }
}
