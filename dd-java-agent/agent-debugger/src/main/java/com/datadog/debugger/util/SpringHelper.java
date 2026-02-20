package com.datadog.debugger.util;

import java.lang.instrument.Instrumentation;

public class SpringHelper {

  public static boolean isSpringUsingOnlyMethodParameters(Instrumentation inst) {
    for (Class<?> clazz : inst.getAllLoadedClasses()) {
      if ("org.springframework.web.bind.annotation.ControllerMappingReflectiveProcessor"
          .equals(clazz.getName())) {
        // If this class (coming from Spring web since version 6) is found loaded it means Spring
        // supports only getting parameter names from the MethodParameter attribute
        return true;
      }
    }
    // class not found, probably no Spring
    return false;
  }
}
