package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.libs.json.JsArray;
import play.api.libs.json.JsBoolean;
import play.api.libs.json.JsNumber;
import play.api.libs.json.JsObject;
import play.api.libs.json.JsString;
import play.api.libs.json.JsValue;
import play.api.mvc.MultipartFormData;
import scala.Function1;
import scala.Tuple2;
import scala.collection.Iterable;
import scala.collection.Iterator;
import scala.collection.Seq;
import scala.compat.java8.JFunction1;
import scala.math.BigDecimal;

public class BodyParserHelpers {

  public static final int MAX_CONVERSION_DEPTH = 10;
  private static final Logger log = LoggerFactory.getLogger(BodyParserHelpers.class);
  public static final int MAX_RECURSION = 15;

  private static JFunction1<
          scala.collection.immutable.Map<String, Seq<String>>,
          scala.collection.immutable.Map<String, Seq<String>>>
      HANDLE_URL_ENCODED = BodyParserHelpers::handleUrlEncoded;
  private static JFunction1<String, String> HANDLE_TEXT = BodyParserHelpers::handleText;
  private static JFunction1<MultipartFormData<?>, MultipartFormData<?>> HANDLE_MULTIPART_FORM_DATA =
      BodyParserHelpers::handleMultipartFormData;
  private static JFunction1<JsValue, JsValue> HANDLE_JSON = BodyParserHelpers::handleJson;

  private BodyParserHelpers() {}

  public static Function1<
          scala.collection.immutable.Map<String, Seq<String>>,
          scala.collection.immutable.Map<String, Seq<String>>>
      getHandleUrlEncodedMapF() {
    return HANDLE_URL_ENCODED;
  }

  private static scala.collection.immutable.Map<String, Seq<String>> handleUrlEncoded(
      scala.collection.immutable.Map<String, Seq<String>> data) {
    if (data == null || data.isEmpty()) {
      return data;
    }

    try {
      Object conv = tryConvertingScalaContainers(data, MAX_CONVERSION_DEPTH);
      handleArbitraryPostData(conv, "tolerantFormUrlEncoded");
    } catch (Exception e) {
      handleException(e, "Error handling result of tolerantFormUrlEncoded BodyParser");
    }
    return data;
  }

  public static Function1<String, String> getHandleStringMapF() {
    return HANDLE_TEXT;
  }

  private static String handleText(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }

    try {
      handleArbitraryPostData(s, "tolerantText");
    } catch (Exception e) {
      handleException(e, "Error handling result of tolerantText BodyParser");
    }

    return s;
  }

  public static Function1<MultipartFormData<?>, MultipartFormData<?>>
      getHandleMultipartFormDataF() {
    return HANDLE_MULTIPART_FORM_DATA;
  }

  private static MultipartFormData<?> handleMultipartFormData(MultipartFormData<?> data) {
    scala.collection.immutable.Map<String, Seq<String>> mpfd = data.asFormUrlEncoded();

    if (mpfd == null || mpfd.isEmpty()) {
      return data;
    }

    try {
      Object conv = tryConvertingScalaContainers(mpfd, MAX_CONVERSION_DEPTH);
      handleArbitraryPostData(conv, "multipartFormData");
    } catch (Exception e) {
      handleException(e, "Error handling result of multipartFormData BodyParser");
    }
    return data;
  }

  public static Function1<JsValue, JsValue> getHandleJsonF() {
    return HANDLE_JSON;
  }

  private static JsValue handleJson(JsValue data) {
    if (data == null) {
      return null;
    }

    try {
      Object conv = jsValueToJavaObject(data, MAX_RECURSION);
      handleArbitraryPostData(conv, "json");
    } catch (Exception e) {
      handleException(e, "Error handling result of json BodyParser");
    }
    return data;
  }

  private static void executeCallback(
      RequestContext reqCtx,
      BiFunction<RequestContext, Object, Flow<Void>> callback,
      Object conv,
      String details) {
    Flow<Void> flow = callback.apply(reqCtx, conv);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
      if (blockResponseFunction != null) {
        boolean success =
            blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
        if (success) {
          throw new BlockingException("Blocked request (for " + details + ")");
        }
      }
    }
  }

  private static Object tryConvertingScalaContainers(Object obj, int depth) {
    if (depth == 0) {
      return obj;
    }
    if (obj instanceof scala.collection.Map) {
      scala.collection.Map map = (scala.collection.Map) obj;
      Map<Object, Object> ret = new HashMap<>();
      Iterator<Tuple2> iterator = map.iterator();
      while (iterator.hasNext()) {
        Tuple2 next = iterator.next();
        ret.put(next._1(), tryConvertingScalaContainers(next._2(), depth - 1));
      }
      return ret;
    } else if (obj instanceof Iterable) {
      List<Object> ret = new ArrayList<>();
      Iterator iterator = ((Iterable) obj).iterator();
      while (iterator.hasNext()) {
        Object next = iterator.next();
        ret.add(tryConvertingScalaContainers(next, depth - 1));
      }
      return ret;
    }
    return obj;
  }

  public static void handleJsonNode(JsonNode n, String source) {
    Object o = jsNodeToJavaObject(n, MAX_RECURSION);
    handleArbitraryPostDataWithSpanError(o, source);
  }

  public static void handleArbitraryPostDataWithSpanError(Object o, String source) {
    AgentSpan span = activeSpan();
    try {
      doHandleArbitraryPostData(span, o, source);
    } catch (BlockingException be) {
      span.addThrowable(be);
      throw be;
    }
  }

  public static void handleArbitraryPostData(Object o, String source) {
    doHandleArbitraryPostData(activeSpan(), o, source);
  }

  public static void doHandleArbitraryPostData(AgentSpan span, Object o, String source) {
    RequestContext reqCtx;
    if (span == null
        || (reqCtx = span.getRequestContext()) == null
        || reqCtx.getData(RequestContextSlot.APPSEC) == null) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    if (callback == null) {
      return;
    }

    // callback execution
    executeCallback(reqCtx, callback, o, source);
  }

  private static void handleException(Exception e, String logMessage) {
    if (e instanceof BlockingException) {
      throw (BlockingException) e;
    }

    log.warn(logMessage, e);
  }

  public static Object jsValueToJavaObject(JsValue value) {
    return jsValueToJavaObject(value, MAX_RECURSION);
  }

  public static Object jsValueToJavaObject(JsValue value, int maxRecursion) {
    if (value == null || maxRecursion <= 0) {
      return null;
    }

    if (value instanceof JsString) {
      return ((JsString) value).value();
    } else if (value instanceof JsNumber) {
      final BigDecimal number = ((JsNumber) value).value();
      return number == null ? null : number.bigDecimal();
    } else if (value instanceof JsBoolean) {
      return ((JsBoolean) value).value();
    } else if (value instanceof JsObject) {
      Map<String, Object> map = new HashMap<>();
      JsObject jsonObject = (JsObject) value;
      Iterator<Tuple2<String, JsValue>> iterator = jsonObject.fields().iterator();
      while (iterator.hasNext()) {
        Tuple2<String, JsValue> e = iterator.next();
        map.put(e._1(), jsValueToJavaObject(e._2(), maxRecursion - 1));
      }
      return map;
    } else if (value instanceof JsArray) {
      List<Object> list = new ArrayList<>();
      JsArray jsArray = (JsArray) value;
      Iterator<JsValue> iterator = jsArray.value().iterator();
      while (iterator.hasNext()) {
        JsValue next = iterator.next();
        list.add(jsValueToJavaObject(next, maxRecursion - 1));
      }
      return list;
    } else {
      return null;
    }
  }

  private static Object jsNodeToJavaObject(JsonNode value, int maxRecursion) {
    if (value == null || maxRecursion <= 0) {
      return null;
    }

    if (value instanceof TextNode) {
      return value.asText();
    } else if (value instanceof FloatNode || value instanceof DoubleNode) {
      return value.asDouble();
    } else if (value instanceof NumericNode) {
      return value.asLong();
    } else if (value instanceof ObjectNode) {
      Map<String, Object> map = new HashMap<>();
      ObjectNode jsonObject = (ObjectNode) value;
      java.util.Iterator<Map.Entry<String, JsonNode>> iterator = jsonObject.fields();
      while (iterator.hasNext()) {
        Map.Entry<String, JsonNode> e = iterator.next();
        map.put(e.getKey(), jsNodeToJavaObject(e.getValue(), maxRecursion - 1));
      }
      return map;
    } else if (value instanceof ArrayNode) {
      List<Object> list = new ArrayList<>();
      ArrayNode arrayNode = (ArrayNode) value;
      java.util.Iterator<JsonNode> iterator = arrayNode.elements();
      while (iterator.hasNext()) {
        JsonNode next = iterator.next();
        list.add(jsNodeToJavaObject(next, maxRecursion - 1));
      }
      return list;
    } else if (value instanceof NullNode) {
      return null;
    } else {
      return value.asText("");
    }
  }
}
