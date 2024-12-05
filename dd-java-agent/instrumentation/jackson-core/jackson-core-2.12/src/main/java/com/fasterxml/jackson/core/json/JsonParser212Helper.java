package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer212Helper;

public final class JsonParser212Helper {
  private JsonParser212Helper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer212Helper.fetchIntern(jsonParser._symbols);
  }
}
