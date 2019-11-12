package datadog.trace.instrumentation.okhttp3;

import datadog.trace.instrumentation.api.AgentSpan;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

import static datadog.trace.instrumentation.api.AgentTracer.activeSpan;

@Slf4j
public class TracingInterceptor implements Interceptor {
  @Override
  public Response intercept(final Chain chain) throws IOException {
    log.error("starting to intercept, guessing this will be called twice per request");
    //this gets called twice per request, once for the normal interceptor, once for the network interceptor
    if (chain.request().header("Datadog-Meta-Lang") != null) {
      //never really seems to go in here
      log.error("there's a chain.request.header(Datadog-Meta-Lang");
      return chain.proceed(chain.request());
    }

    Response response = null;

    // application interceptor?
    if (chain.connection() == null) {
      //if you use  builder.addInterceptor(interceptor); in instrumentation, you enter here first
      log.error("there was no chain.connection()");
      //final AgentSpan span = startSpan("okhttp.http");
      //    DECORATE.afterStart(span);

      final Request.Builder requestBuilder = chain.request().newBuilder();

      final Object tag = chain.request().tag();

      final AgentSpan span = activeSpan();

      final TagWrapper tagWrapper =
        tag instanceof TagWrapper ? (TagWrapper) tag : new TagWrapper(tag);
      requestBuilder.tag(new TagWrapper(tagWrapper, span));
      //there's an issue that span, which tracer.activeSpan() returns, and AgentSpan, which is required fo the requestBuilder.tag are different.


      //original code:
//      final TagWrapper tagWrapper =
//        tag instanceof TagWrapper ? (TagWrapper) tag : new TagWrapper(tag);
//      requestBuilder.tag(new TagWrapper(tagWrapper, span));
//commenting out the activateSpan parts just throws the proceed error. We do enter this and then enter a second time
      //with network interceptor, but the tagwrapper we pull is not complete with the span information.
      //try getting scope from current span not by activating a new span.


//      final AgentScope scope = activateSpan(span, true);
      response = chain.proceed(requestBuilder.build());
      //activeScope().close();
//      try {
//        response = chain.proceed(requestBuilder.build());
//      } catch (final Throwable ex) {
//        DECORATE.onError(activeScope(), ex);
//        throw ex;
//      } finally {
//        DECORATE.beforeFinish(activeScope());
//        activeScope().close();
//      }
    } else {
      final Object tag = chain.request().tag();
      log.error("the value of tag is " + tag.toString());
      if (tag instanceof TagWrapper) {
        log.error("it was a TagWrapper");
        //you enter here on the second run through which is with the networkinterceptor
        final TagWrapper tagWrapper = (TagWrapper) tag;
        response =
          //this is where the proceed() function is called from the intercept function this needs a context which we're pulling from the tagWrapper, the tagWrapper
          //may also be able to just get span from tracer here for context.
          new TracingCallFactory.NetworkInterceptor(tagWrapper.getSpan().context())
            .intercept(chain);
      } else {

        //Two options: 1. we just make the network span from scratch, issue here is that I'm not sure the network interceptor
        //grabs all of the information we need since we're pulling some info from the normal interceptor span.

        //2. Create interceptor span in some way that doesn't actually create a span or filter the span,
        // then create the network span from that still.

        //the first interceptor provides the TagWrapper and span andd context for the network adapter. One way to solve this would be to either have whatever special thing the tracer
//if you try and use double builder.addNetworkInterceptor(interceptor); you only enter the intercept method once and then hit the final else block, I guess since proceed is not called for the
        //interceptor it throws an error

        //I think this is probably the function being called for the interceptor and then chain interceptor: https://github.com/square/okhttp/blob/master/okhttp/src/main/java/okhttp3/OkHttpClient.kt#L531-L548

        //if we don't have builder.addInterceptor(interceptor); then we enter here, meaning that somehow builder.addInterceptor leads to a TagWrapper object being placed in the Chain object
        //when we run with both interceptors added, the tagwrapper is printed once per span, meaning I think that in the above large if, the first interceptor must be setting the tagwrapper
        //somehow
        //if network interceptor only: the value of tag is Request{method=GET, url=http://localhost:9393/ServiceD, tag=null}
        //if both: the value of tag is datadog.trace.instrumentation.okhttp3.TagWrapper@7e1d6cf6

        //ah ok, so our first run through with this, we create the normal intercept okhttp span?? and create a TagWrapper object, then the second run through with the network interceptor
        //we call proceed which makes it happy. huh what if you just didn't create the span for the first run through but still created and place the TagWrapper object?
        //hmmm it seems that whatever .addInterceptor does is different somewhere inside of okhttp or that we do something differently somewhere based off it. Can't call either
        // .addInterceptor(two of the same span and no distributed tracing) or
        //.addNetworkInterceptor(nothing) twice without things breaking.
        log.error("tag is null or not an instance of TagWrapper, skipping decorator onResponse()");
      }
    }

    return response;
  }

}
