package com.datadog.debugger.symboltest;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public record SymbolExtraction15(String firstName, String lastName, int age) {

  public static int main(String arg) {
    return 42;
  }
}
