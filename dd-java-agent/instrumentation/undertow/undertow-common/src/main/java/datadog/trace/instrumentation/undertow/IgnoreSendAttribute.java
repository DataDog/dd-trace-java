package datadog.trace.instrumentation.undertow;

import io.undertow.util.AttachmentKey;

public enum IgnoreSendAttribute {
  INSTANCE;

  public static final AttachmentKey<IgnoreSendAttribute> IGNORE_SEND_KEY =
      AttachmentKey.create(IgnoreSendAttribute.class);
}
