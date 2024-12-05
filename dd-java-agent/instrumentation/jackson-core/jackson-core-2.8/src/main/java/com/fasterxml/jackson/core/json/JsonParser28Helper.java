package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer28Helper;

public final class JsonParser28Helper {
  private JsonParser28Helper() {}

  public static boolean fetchIntern(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer28Helper.fetchIntern(jsonParser._symbols);
  }
}
