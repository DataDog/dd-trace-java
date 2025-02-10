package datadog.trace.civisibility.config;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;

public enum TestSettings {
  FLAKY(1, "flaky"),
  KNOWN(2, "known"),
  QUARANTINED(4, "quarantined"),
  DISABLED(8, "disabled"),
  ATTEMPT_TO_FIX(16, "attempt_to_fix");

  private final int flag;
  private final String name;

  TestSettings(int flag, String name) {
    this.flag = flag;
    this.name = name;
  }

  public String asString() {
    return name;
  }

  public static int addSetting(int flag, TestSettings setting) {
    return flag | setting.flag;
  }

  public static boolean isSetting(int flag, TestSettings setting) {
    return (flag & setting.flag) != 0;
  }

  public static class TestSettingsSerializer {
    public static void serialize(Serializer serializer, TestSettings setting) {
      serializer.write(setting.flag);
    }

    public static TestSettings deserialize(ByteBuffer buf) {
      int flag = Serializer.readInt(buf);
      for (TestSettings setting : TestSettings.values()) {
        if (setting.flag == flag) {
          return setting;
        }
      }
      return null;
    }
  }
}
