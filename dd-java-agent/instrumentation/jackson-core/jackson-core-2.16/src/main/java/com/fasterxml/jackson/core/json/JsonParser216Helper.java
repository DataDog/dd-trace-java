package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer216Helper;

public final class JsonParser216Helper {
  private JsonParser216Helper() {}

  public static boolean fetchInterner(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizer216Helper.fetchInterner(jsonParser._symbols);
  }
}
