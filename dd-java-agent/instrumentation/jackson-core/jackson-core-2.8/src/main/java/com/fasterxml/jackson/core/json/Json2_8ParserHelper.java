package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer2_8Helper;

public final class Json2_8ParserHelper {
  private Json2_8ParserHelper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer2_8Helper.fetchIntern(jsonParser._symbols);
  }
}
