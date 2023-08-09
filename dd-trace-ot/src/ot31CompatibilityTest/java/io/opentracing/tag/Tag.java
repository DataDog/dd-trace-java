package io.opentracing.tag;

// So Groovy and/or Spock will eagerly try to look at all methods and fields of
// a class, even though the JVM is perfectly fine with defining a class that has
// a method with types it doesn't know about yet. Hence, we need to have this type
// that was introduced in 0.32.0, here for the 0.31.0 test to even load properly.
public interface Tag<T> {}
