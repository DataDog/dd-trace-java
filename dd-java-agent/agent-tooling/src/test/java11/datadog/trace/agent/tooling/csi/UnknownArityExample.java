package datadog.trace.agent.tooling.csi;

import datadog.trace.util.RandomUtils;
import java.util.function.Supplier;

public class UnknownArityExample implements Supplier<String> {

  @Override
  public String get() {
    final String name = RandomUtils.randomUUID().toString();
    final long height = Math.round(Math.random() * 200);
    final double weight = Math.random() * 100;
    final int age = (int) Math.round(Math.random() * 100);
    return "My name is "
        + name
        + ", I'm "
        + age
        + " years old, "
        + height
        + " cm tall and I weight "
        + weight
        + " kg";
  }
}
