package datadog.trace.civisibility.ipc.serialization;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PolymorphicSerializer<ParentType extends SerializableType> {
  private final Map<Byte, Function<ByteBuffer, ? extends ParentType>> deserializers =
      new HashMap<>();
  private final Map<Class<? extends ParentType>, Byte> ids = new HashMap<>();

  @SafeVarargs
  public PolymorphicSerializer(Class<? extends ParentType>... types) {
    for (Class<? extends ParentType> type : types) {
      register(type);
    }
  }

  private <T extends ParentType> void register(Class<T> type) {
    byte id = (byte) (ids.size() + 1);
    Function<ByteBuffer, T> deserializer = findDeserializer(type);
    deserializers.put(id, deserializer);
    ids.put(type, id);
  }

  private <T extends ParentType> Function<ByteBuffer, T> findDeserializer(Class<T> type) {
    for (Method method : type.getDeclaredMethods()) {
      boolean isStatic = (method.getModifiers() & Modifier.STATIC) != 0;
      Class<?>[] parameterTypes = method.getParameterTypes();
      if (isStatic
          && method.getReturnType() == type
          && parameterTypes.length == 1
          && parameterTypes[0] == ByteBuffer.class) {
        return toDeserializer(method);
      }
    }
    throw new IllegalArgumentException(
        "Could not find a static method that accepts ByteBuffer and returns "
            + type.getName()
            + "in "
            + type.getName());
  }

  @SuppressWarnings("unchecked")
  private <T extends ParentType> Function<ByteBuffer, T> toDeserializer(Method method) {
    return bb -> {
      try {
        return (T) method.invoke(null, bb);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  public void serialize(ParentType o, Serializer s) {
    if (o == null) {
      s.write(0);
      return;
    }
    Byte id = ids.get(o.getClass());
    if (id == null) {
      throw new IllegalArgumentException("Unknown type: " + o.getClass().getName());
    }
    s.write(id);
    o.serialize(s);
  }

  public ParentType deserialize(ByteBuffer buffer) {
    byte id = Serializer.readByte(buffer);
    if (id == 0) {
      return null;
    }
    Function<ByteBuffer, ? extends ParentType> deserializer = deserializers.get(id);
    if (deserializer == null) {
      throw new IllegalArgumentException("Unknown type ID: " + id);
    }
    return deserializer.apply(buffer);
  }
}
