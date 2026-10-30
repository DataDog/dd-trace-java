package datadog.trace.instrumentation.jackson.blackbird;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

final class Differential {
  private static final String BLACKBIRD_PACKAGE = "com.fasterxml.jackson.module.blackbird.";

  private Differential() {}

  static final class PlainObjectMapper extends ObjectMapper {
    private static final long serialVersionUID = 1L;
  }

  static ObjectMapper plain(Corpus.Setup setup) {
    final ObjectMapper m = new PlainObjectMapper();
    setup.configure.accept(m);
    assertFalse(BlackbirdRegistrar.hasAccessorModule(m), "baseline mapper must not have Blackbird");
    return m;
  }

  static Stream<Arguments> setupsAndCases() {
    final List<Arguments> args = new ArrayList<>();
    for (Corpus.Setup s : Corpus.setups()) {
      for (Corpus.Case c : Corpus.cases()) {
        args.add(Arguments.of(s, c));
      }
    }
    return args.stream();
  }

  static Stream<Arguments> setupsAndInputs() {
    final List<Arguments> args = new ArrayList<>();
    for (Corpus.Setup s : Corpus.setups()) {
      for (Corpus.Input in : Corpus.inputs()) {
        args.add(Arguments.of(s, in));
      }
    }
    return args.stream();
  }

  private static ObjectWriter writer(ObjectMapper m, Corpus.Case c) {
    final ObjectWriter w = m.writerFor(m.getTypeFactory().constructType(c.type));
    return c.view == null ? w : w.withView(c.view);
  }

  private static ObjectReader reader(ObjectMapper m, Corpus.Case c) {
    final ObjectReader r = m.readerFor(m.getTypeFactory().constructType(c.type));
    return c.view == null ? r : r.withView(c.view);
  }

  static void assertSameOutput(ObjectMapper plain, ObjectMapper blackbird, Corpus.Case c)
      throws Exception {
    for (int pass = 0; pass < 2; pass++) {
      final byte[] expected = writer(plain, c).writeValueAsBytes(c.value);
      final byte[] actual = writer(blackbird, c).writeValueAsBytes(c.value);
      assertEquals(
          new String(expected, StandardCharsets.UTF_8),
          new String(actual, StandardCharsets.UTF_8),
          c.name + ": serialized bytes differ");
      assertEquals(expected.length, actual.length);

      final Object readPlain = reader(plain, c).readValue(expected);
      final Object readBlackbird = reader(blackbird, c).readValue(expected);
      assertThat(readBlackbird)
          .as(c.name + ": deserialized values differ")
          .usingRecursiveComparison()
          .isEqualTo(readPlain);
      assertEquals(
          new String(expected, StandardCharsets.UTF_8),
          writer(plain, c).writeValueAsString(readBlackbird),
          c.name + ": round trip through the Blackbird mapper changed the value");
      assertEquals(
          new String(expected, StandardCharsets.UTF_8),
          writer(blackbird, c).writeValueAsString(readPlain),
          c.name + ": round trip through the plain mapper changed the value");
    }
  }

  static void assertSameRead(ObjectMapper plain, ObjectMapper blackbird, Corpus.Input in) {
    final Object[] p = read(plain, in);
    final Object[] b = read(blackbird, in);
    if (p[1] != null || b[1] != null) {
      assertTrue(p[1] != null && b[1] != null, in.name + ": only one mapper threw: " + p[1] + b[1]);
      assertEquals(p[1].getClass(), b[1].getClass(), in.name + ": exception types differ");
      assertEquals(
          ((Throwable) p[1]).getMessage(),
          ((Throwable) b[1]).getMessage(),
          in.name + ": exception messages differ");
      return;
    }
    assertThat(b[0]).as(in.name).usingRecursiveComparison().isEqualTo(p[0]);
  }

  private static Object[] read(ObjectMapper m, Corpus.Input in) {
    try {
      return new Object[] {
        m.readerFor(m.getTypeFactory().constructType(in.type)).readValue(in.json), null
      };
    } catch (Exception e) {
      return new Object[] {null, e};
    }
  }

  static void assertBlackbirdAccessorsInUse(ObjectMapper m) throws Exception {
    final JsonSerializer<Object> ser =
        m.getSerializerProviderInstance().findValueSerializer(Corpus.SmallBean.class);
    int optimizedWriters = 0;
    for (Iterator<PropertyWriter> it = ser.properties(); it.hasNext(); ) {
      if (it.next().getClass().getName().startsWith(BLACKBIRD_PACKAGE)) {
        optimizedWriters++;
      }
    }
    assertTrue(optimizedWriters > 0, "no Blackbird property writers on " + ser);

    final JavaType type = m.constructType(Corpus.SmallBean.class);
    final JsonDeserializer<Object> deser =
        ((DefaultDeserializationContext) m.getDeserializationContext())
            .createInstance(m.getDeserializationConfig(), null, m.getInjectableValues())
            .findRootValueDeserializer(type);
    int optimizedSetters = 0;
    for (Iterator<SettableBeanProperty> it =
            ((com.fasterxml.jackson.databind.deser.BeanDeserializerBase) deser).properties();
        it.hasNext(); ) {
      if (it.next().getClass().getName().startsWith(BLACKBIRD_PACKAGE)) {
        optimizedSetters++;
      }
    }
    assertTrue(optimizedSetters > 0, "no Blackbird setters on " + deser);
  }
}
