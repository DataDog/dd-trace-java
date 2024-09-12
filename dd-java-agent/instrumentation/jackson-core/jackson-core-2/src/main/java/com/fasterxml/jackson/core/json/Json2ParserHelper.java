package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.BytesToNameCanonicalizer2Helper;

public final class Json2ParserHelper {
  private Json2ParserHelper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return BytesToNameCanonicalizer2Helper.fetchIntern(jsonParser._symbols);
  }
}
