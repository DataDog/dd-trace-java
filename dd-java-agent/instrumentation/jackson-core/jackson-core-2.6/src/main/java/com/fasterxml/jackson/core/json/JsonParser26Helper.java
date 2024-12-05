package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer26Helper;

public final class JsonParser26Helper {
  private JsonParser26Helper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer26Helper.fetchIntern(jsonParser._symbols);
  }
}
