package datadog.trace.civisibility.config;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;

public enum TestSetting {
  FLAKY(1, "flaky"),
  KNOWN(2, "known"),
  QUARANTINED(4, "quarantined"),
  DISABLED(8, "disabled"),
  ATTEMPT_TO_FIX(16, "attempt_to_fix");

  private final int flag;
  private final String name;

  TestSetting(int flag, String name) {
    this.flag = flag;
    this.name = name;
  }

  public int getFlag() {
    return flag;
  }

  public String asString() {
    return name;
  }

  public static class TestSettingsSerializer {
    public static void serialize(Serializer serializer, TestSetting setting) {
      serializer.write(setting.flag);
    }

    public static TestSetting deserialize(ByteBuffer buf) {
      int flag = Serializer.readInt(buf);
      for (TestSetting setting : TestSetting.values()) {
        if (setting.flag == flag) {
          return setting;
        }
      }
      return null;
    }
  }
}
