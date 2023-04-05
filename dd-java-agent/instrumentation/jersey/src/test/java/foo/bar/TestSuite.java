package foo.bar;

import org.glassfish.jersey.internal.inject.ParamConverters.StringConstructor;

public class TestSuite {
  public static String convertString(String s) {
    return new StringConstructor().getConverter(String.class, null, null).fromString(s);
  }
}
