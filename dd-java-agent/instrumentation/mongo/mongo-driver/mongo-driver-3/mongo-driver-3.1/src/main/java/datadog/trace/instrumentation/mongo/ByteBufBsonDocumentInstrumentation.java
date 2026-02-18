package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientOptions;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.bson.BsonDocument;
import org.bson.ByteBuf;

@AutoService(InstrumenterModule.class)
public class ByteBufBsonDocumentInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {

  public ByteBufBsonDocumentInstrumentation() {
    super("mongo");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.connection.ByteBufBsonDocument";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("byteBuf"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.bson.BsonDocument", "org.bson.ByteBuf");
  }

  @Override
  public String muzzleDirective() {
    return "driver-only";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ExposeBuffer");
  }

  public static final class ExposeBuffer {
    @Advice.OnMethodExit
    public static void exposeBuffer(
        @Advice.This BsonDocument doc, @Advice.FieldValue("byteBuf") ByteBuf byteBuf) {
      InstrumentationContext.get(BsonDocument.class, ByteBuf.class).put(doc, byteBuf);
    }

    public static void muzzleCheck() {
      MongoClientOptions.builder().addCommandListener(null).build();
    }
  }
}
