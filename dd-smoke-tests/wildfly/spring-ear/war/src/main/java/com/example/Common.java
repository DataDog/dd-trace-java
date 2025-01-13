package com.example;

import java.util.concurrent.atomic.AtomicBoolean;

public class Common {
  // for the sake of this example it avoids boilerplate ton inject an ejb into a spring context
  public static final AtomicBoolean ENABLED = new AtomicBoolean(false);
}
