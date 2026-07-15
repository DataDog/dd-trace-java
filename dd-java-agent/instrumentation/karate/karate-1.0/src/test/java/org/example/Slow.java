package org.example;

public class Slow {

  public static void stall() {
    try {
      Thread.sleep(1100);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
