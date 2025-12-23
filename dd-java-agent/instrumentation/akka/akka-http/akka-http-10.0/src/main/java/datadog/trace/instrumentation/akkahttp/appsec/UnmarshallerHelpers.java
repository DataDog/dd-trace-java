package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.MediaType;
import akka.http.javadsl.model.MediaTypes;
import akka.http.scaladsl.common.StrictForm;
import akka.http.scaladsl.model.FormData;
import akka.http.scaladsl.model.HttpEntity;
import akka.http.scaladsl.unmarshalling.Unmarshaller;
import akka.http.scaladsl.unmarshalling.Unmarshaller$;
import akka.japi.JavaPartialFunction;
import akka.stream.Materializer;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Function1;
import scala.PartialFunction;
import scala.Tuple2;
import scala.collection.Iterable;
import scala.collection.Iterator;
import scala.compat.java8.JFunction1;
import scala.compat.java8.JFunction2;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

public class UnmarshallerHelpers {

  public static final int MAX_CONVERSION_DEPTH = 10;
  private static final Logger log = LoggerFactory.getLogger(UnmarshallerHelpers.class);

  private static final MediaType APPLICATION_X_WWW_FORM_URLENCODED;

  static {
    MediaType t = null;
    try {
      // subtype of MediaType changes between 10.0 and 10.1
      Field f = MediaTypes.class.getField("APPLICATION_X_WWW_FORM_URLENCODED");
      t = (MediaType) f.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
    }
    APPLICATION_X_WWW_FORM_URLENCODED = t;
  }

  private UnmarshallerHelpers() {}

  public static Unmarshaller<HttpEntity, akka.http.scaladsl.model.FormData>
      transformUrlEncodedUnmarshaller(
          Unmarshaller<HttpEntity, akka.http.scaladsl.model.FormData> original) {
    JFunction1<akka.http.scaladsl.model.FormData, akka.http.scaladsl.model.FormData> mapf =
        formData -> {
          try {
            handleFormData(formData);
          } catch (Exception e) {
            handleException(e, "transformUrlEncodedMarshaller");
          }

          return formData;
        };

    return original.map(mapf);
  }

  private static void handleFormData(FormData formData) {
    AgentSpan span = activeSpan();
    RequestContext reqCtx;
    if (span == null
        || (reqCtx = span.getRequestContext()) == null
        || reqCtx.getData(RequestContextSlot.APPSEC) == null
        || isStrictFormOngoing(span)) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    if (callback == null) {
      return;
    }

    Iterator<Tuple2<String, String>> fieldsIter = formData.fields().iterator();
    Map<String, List<String>> conv = new HashMap<>();
    while (fieldsIter.hasNext()) {
      Tuple2<String, String> pair = fieldsIter.next();

      String key = pair._1;
      List<String> values = conv.get(key);
      if (values == null) {
        values = new ArrayList<>();
        conv.put(key, values);
      }
      values.add(pair._2);
    }

    if (conv.isEmpty()) {
      return;
    }

    // callback execution
    executeCallback(reqCtx, callback, conv, "urlEncodedFormDataUnmarshaller");
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
          if (blockResponseFunction instanceof AkkaBlockResponseFunction) {
            AkkaBlockResponseFunction abrf = (AkkaBlockResponseFunction) blockResponseFunction;
            abrf.setUnmarshallBlock(true);
          }
          throw new BlockingException("Blocked request (for " + details + ")");
        }
      }
    }
  }

  public static Unmarshaller transformMultipartFormDataUnmarshaller(Unmarshaller original) {
    JFunction1<
            akka.http.scaladsl.model.Multipart.FormData,
            akka.http.scaladsl.model.Multipart.FormData>
        mapf =
            t -> {
              if (!(t instanceof akka.http.scaladsl.model.Multipart$FormData$Strict)) {
                // data not loaded yet...
                // it's not practical to wrap the object
                // rely on instrumentation on toStrict
                return t;
              }

              try {
                handleMultipartStrictFormData(
                    (akka.http.scaladsl.model.Multipart$FormData$Strict) t);
              } catch (Exception e) {
                handleException(e, "Error in handleMultipartStrictFormData");
              }

              return t;
            };

    return original.map(mapf);
  }

  public static scala.concurrent.Future<akka.http.scaladsl.model.Multipart$FormData$Strict>
      transformMultiPartFormDataToStrictFuture(
          scala.concurrent.Future<akka.http.scaladsl.model.Multipart$FormData$Strict> future,
          Materializer materializer) {
    JFunction1<
            akka.http.scaladsl.model.Multipart$FormData$Strict,
            akka.http.scaladsl.model.Multipart$FormData$Strict>
        mapf =
            t -> {
              try {
                AgentSpan span = activeSpan();
                if (span != null && !isStrictFormOngoing(span)) {
                  handleMultipartStrictFormData(t);
                }
              } catch (Exception e) {
                handleException(e, "Error in transformMultiPartFormDataToStrictFuture");
              }
              return t;
            };
    return future.map(mapf, materializer.executionContext());
  }

  private static void handleMultipartStrictFormData(
      akka.http.scaladsl.model.Multipart$FormData$Strict st) {
    AgentSpan span = activeSpan();
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

    // conversion to map string -> list of string
    java.lang.Iterable<akka.http.javadsl.model.Multipart.FormData.BodyPart.Strict> strictParts =
        st.getStrictParts();
    Map<String, List<String>> conv = new HashMap<>();
    for (akka.http.javadsl.model.Multipart.FormData.BodyPart.Strict part : strictParts) {
      akka.http.javadsl.model.HttpEntity.Strict entity = part.getEntity();
      if (!(entity instanceof HttpEntity.Strict)) {
        continue;
      }

      HttpEntity.Strict sentity = (HttpEntity.Strict) entity;

      String name = part.getName();
      List<String> curStrings = conv.get(name);
      if (curStrings == null) {
        curStrings = new ArrayList<>();
        conv.put(name, curStrings);
      }

      String s =
          sentity
              .getData()
              .decodeString(
                  Unmarshaller$.MODULE$.bestUnmarshallingCharsetFor(sentity).nioCharset());
      curStrings.add(s);
    }

    // callback execution
    executeCallback(reqCtx, callback, conv, "multipartFormDataUnmarshaller");
  }

  public static Unmarshaller<HttpEntity, String> transformStringUnmarshaller(
      Unmarshaller<HttpEntity, String> original) {
    Unmarshaller.EnhancedUnmarshaller<HttpEntity, String> enhancedOriginal =
        new Unmarshaller.EnhancedUnmarshaller<>(original);
    JFunction2<HttpEntity, String, String> f2 =
        (entity, str) -> {
          try {
            AgentSpan agentSpan = activeSpan();
            if (agentSpan == null || isStrictFormOngoing(agentSpan)) {
              return str;
            }

            ContentType contentType = entity.getContentType();
            MediaType mediaType = contentType.mediaType();
            if (mediaType != MediaTypes.APPLICATION_JSON
                && mediaType != MediaTypes.MULTIPART_FORM_DATA
                && mediaType != APPLICATION_X_WWW_FORM_URLENCODED) {
              handleArbitraryPostData(str, "HttpEntity -> String unmarshaller");
            }
          } catch (Exception e) {
            handleException(e, "Error in transformStringUnmarshaller");
          }

          return str;
        };

    return enhancedOriginal.mapWithInput(f2);
  }

  public static akka.http.javadsl.unmarshalling.Unmarshaller transformJacksonUnmarshaller(
      akka.http.javadsl.unmarshalling.Unmarshaller original) {
    return original.thenApply(
        ret -> {
          try {
            handleArbitraryPostData(ret, "jackson unmarshaller");
          } catch (Exception e) {
            handleException(e, "Error in transformJacksonUnmarshaller");
          }
          return ret;
        });
  }

  public static Unmarshaller transformArbitrarySprayUnmarshaller(Unmarshaller original) {
    JFunction1<Object, Object> f =
        ret -> {
          Object conv = tryConvertingScalaContainers(ret, MAX_CONVERSION_DEPTH);
          try {
            handleArbitraryPostData(conv, "spray unmarshaller");
          } catch (Exception e) {
            handleException(e, "Error in transformArbitrarySprayUnmarshaller");
          }
          return ret;
        };
    return original.map(f);
  }

  private static final WeakHashMap<RequestContext, Boolean> STRICT_FORM_SERIALIZATION_ONGOING =
      new WeakHashMap<>();

  // when unmarshalling parts of multipart requests, some other unmarshallers that
  // we also instrument, like the string unmarshaller, can run. Those runs happen
  // in a subset of the data, so we are not interested in them: instead we want
  // to submit all the data at the same time, after having finished fully
  // unmarshalling the StrictForm. We suppress the sub-runs of unmarshallers by
  // noticing when the StrictForm unmarshaller starts running
  private static void markStrictFormOngoing(AgentSpan agentSpan) {
    synchronized (STRICT_FORM_SERIALIZATION_ONGOING) {
      STRICT_FORM_SERIALIZATION_ONGOING.put(agentSpan.getRequestContext(), Boolean.TRUE);
    }
  }

  private static void unmarkStrictFormOngoing(AgentSpan agentSpan) {
    synchronized (STRICT_FORM_SERIALIZATION_ONGOING) {
      STRICT_FORM_SERIALIZATION_ONGOING.remove(agentSpan.getRequestContext());
    }
  }

  private static boolean isStrictFormOngoing(AgentSpan agentSpan) {
    synchronized (STRICT_FORM_SERIALIZATION_ONGOING) {
      return STRICT_FORM_SERIALIZATION_ONGOING.getOrDefault(
          agentSpan.getRequestContext(), Boolean.FALSE);
    }
  }

  private static JFunction1<StrictForm, StrictForm> STRICT_FORM_DATA_POST_TRANSF =
      sf -> {
        try {
          handleStrictFormData(sf);
        } catch (Exception e) {
          handleException(e, "Error in transformStrictFromUnmarshaller");
        }
        // we do not remove the span from STRICT_FORM_SERIALIZATION_ONGOING,
        // as the string unmarshaller can still run afterwards. This way, the
        // advice will still be skipped
        return sf;
      };

  public static class UnmarkStrictFormOngoingOnUnsupportedException
      extends JavaPartialFunction<Throwable, StrictForm> {
    public static final PartialFunction<Throwable, StrictForm> INSTANCE =
        new UnmarkStrictFormOngoingOnUnsupportedException();

    @Override
    public StrictForm apply(Throwable x, boolean isCheck) throws Exception {
      if (!(x
          instanceof
          akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException)) {
        throw noMatch();
      }
      if (isCheck) {
        return null;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan != null) {
        unmarkStrictFormOngoing(agentSpan);
      }
      throw (Exception) x;
    }
  }

  public static Unmarshaller<HttpEntity, StrictForm> transformStrictFormUnmarshaller(
      Unmarshaller<HttpEntity, StrictForm> original) {
    JFunction1<ExecutionContext, Function1<Materializer, Function1<HttpEntity, Future<StrictForm>>>>
        wrappedBeforeF =
            ec -> {
              JFunction1<Materializer, Function1<HttpEntity, Future<StrictForm>>> g =
                  mat -> {
                    JFunction1<HttpEntity, Future<StrictForm>> h =
                        entity -> {
                          AgentSpan agentSpan = activeSpan();
                          if (agentSpan != null) {
                            markStrictFormOngoing(agentSpan);
                          }

                          Future<StrictForm> resFut = original.apply(entity, ec, mat);
                          return resFut
                              .recover(UnmarkStrictFormOngoingOnUnsupportedException.INSTANCE, ec)
                              .map(STRICT_FORM_DATA_POST_TRANSF, ec);
                        };
                    return h;
                  };
              return g;
            };
    Unmarshaller<HttpEntity, StrictForm> wrapped =
        Unmarshaller$.MODULE$.withMaterializer(wrappedBeforeF);

    return wrapped;
  }

  private static void handleStrictFormData(StrictForm sf) {
    Iterator<Tuple2<String, StrictForm.Field>> iterator = sf.fields().iterator();
    Map<String, List<String>> conv = new HashMap<>();
    while (iterator.hasNext()) {
      Tuple2<String, StrictForm.Field> next = iterator.next();
      String fieldName = next._1();
      StrictForm.Field field = next._2();

      List<String> strings = conv.get(fieldName);
      if (strings == null) {
        strings = new ArrayList<>();
        conv.put(fieldName, strings);
      }

      Object strictFieldValue;
      try {
        Field f = field.getClass().getDeclaredField("value");
        f.setAccessible(true);
        strictFieldValue = f.get(field);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        continue;
      }

      if (strictFieldValue instanceof String) {
        strings.add((String) strictFieldValue);
      } else if (strictFieldValue
          instanceof akka.http.scaladsl.model.Multipart$FormData$BodyPart$Strict) {
        HttpEntity.Strict sentity =
            ((akka.http.scaladsl.model.Multipart$FormData$BodyPart$Strict) strictFieldValue)
                .entity();
        String s =
            sentity
                .getData()
                .decodeString(
                    Unmarshaller$.MODULE$.bestUnmarshallingCharsetFor(sentity).nioCharset());
        strings.add(s);
      }
    }

    handleArbitraryPostData(conv, "HttpEntity -> StrictForm unmarshaller");
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
    } else if (obj instanceof scala.collection.Iterable) {
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

  private static void handleArbitraryPostData(Object o, String source) {
    AgentSpan span = activeSpan();
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
}
