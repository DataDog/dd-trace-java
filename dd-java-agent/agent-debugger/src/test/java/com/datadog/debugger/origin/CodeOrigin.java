package com.datadog.debugger.origin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
public @interface CodeOrigin {}
