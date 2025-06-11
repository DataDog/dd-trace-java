package jvmbootstraptest;

import java.util.ArrayList;
import java.util.List;

public class UnloadingChecker {

  public static void main(final String[] args) {
    final Thread t =
        new Thread(
            () -> {
              final List<byte[]> list = new ArrayList<>();
              while (true) {
                list.add(new byte[100 * 1024 * 1024]);
              }
            });
    t.start();
    try {
      t.join(30_000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
