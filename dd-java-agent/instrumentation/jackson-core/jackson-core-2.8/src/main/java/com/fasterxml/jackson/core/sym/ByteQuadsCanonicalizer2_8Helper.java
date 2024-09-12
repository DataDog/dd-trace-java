package com.fasterxml.jackson.core.sym;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ByteQuadsCanonicalizer2_8Helper {
  private ByteQuadsCanonicalizer2_8Helper() {}

  private static final Logger log = LoggerFactory.getLogger(ByteQuadsCanonicalizer2_8Helper.class);

  private static final Field INTERN = prepareIntern();

  private static Field prepareIntern() {
    Field _intern = null;
    try {
      _intern = ByteQuadsCanonicalizer.class.getDeclaredField("_intern");
      _intern.setAccessible(true);
    } catch (Throwable e) {
      log.debug("Failed to get ByteQuadsCanonicalizer _intern field", e);
      return null;
    }
    return _intern;
  }

  public static boolean fetchIntern(ByteQuadsCanonicalizer symbols) {
    if (INTERN == null) {
      return false;
    }
    try {
      return (boolean) INTERN.get(symbols);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}
