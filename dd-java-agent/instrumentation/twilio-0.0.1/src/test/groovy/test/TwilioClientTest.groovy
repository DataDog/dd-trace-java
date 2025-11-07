package test

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.util.concurrent.ListenableFuture
import com.twilio.Twilio
import com.twilio.exception.ApiException
import com.twilio.http.NetworkHttpClient
import com.twilio.http.Response
import com.twilio.http.TwilioRestClient
import com.twilio.rest.api.v2010.account.Call
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.http.HttpEntity
import org.apache.http.HttpStatus
import org.apache.http.StatusLine
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

abstract class TwilioClientTest extends VersionedNamingTestBase {

  // Made up Twilio Account IDs and Auth token
  final static String SHORT_SID = "abc"
  final static String LONG_SID = "AC0123456789abcdef0123456789abcdef"
  final static String AUTH_TOKEN = "efg"

  final static String MESSAGE_RESPONSE_BODY = """
    {
      "account_sid": "$LONG_SID",
      "api_version": "2010-04-01",
      "body": "Hello, World!",
      "date_created": "Thu, 30 Jul 2015 20:12:31 +0000",
      "date_sent": "Thu, 30 Jul 2015 20:12:33 +0000",
      "date_updated": "Thu, 30 Jul 2015 20:12:33 +0000",
      "direction": "outbound-api",
      "from": "+14155552345",
      "messaging_service_sid": "MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      "num_media": "0",
      "num_segments": "1",
      "price": -0.00750,
      "price_unit": "USD",
      "sid": "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      "status": "sent",
      "subresource_uris": {
        "media": "/2010-04-01/Accounts/$LONG_SID/Messages/SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Media.json"
      },
      "to": "+14155552345",
      "uri": "/2010-04-01/Accounts/$LONG_SID/Messages/SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX.json"
    }
    """

  final static String CALL_RESPONSE_BODY = """
    {
      "account_sid": "ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      "annotation": null,
      "answered_by": null,
      "api_version": "2010-04-01",
      "caller_name": null,
      "date_created": "Tue, 31 Aug 2010 20:36:28 +0000",
      "date_updated": "Tue, 31 Aug 2010 20:36:44 +0000",
      "direction": "inbound",
      "duration": "15",
      "end_time": "Tue, 31 Aug 2010 20:36:44 +0000",
      "forwarded_from": "+141586753093",
      "from": "+15017122661",
      "from_formatted": "(501) 712-2661",
      "group_sid": null,
      "parent_call_sid": null,
      "phone_number_sid": "PNXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      "price": -0.03000,
      "price_unit": "USD",
      "sid": "CAXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      "start_time": "Tue, 31 Aug 2010 20:36:29 +0000",
      "status": "completed",
      "subresource_uris": {
        "notifications": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Calls/CAXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Notifications.json",
        "recordings": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Calls/CAXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Recordings.json",
        "feedback": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Calls/CAXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Feedback.json",
        "feedback_summaries": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Calls/FeedbackSummary.json"
      },
      "to": "+15558675310",
      "to_formatted": "(555) 867-5310",
      "uri": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Calls/CAXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX.json"
    }
    """

  final static String ERROR_RESPONSE_BODY = """
    {
      "code": 123,
      "message": "Testing Failure",
      "code": 567,
      "more_info": "Testing"
    }
    """

  TwilioRestClient twilioRestClient = Mock()

  def setupSpec() {
    Twilio.init(SHORT_SID, AUTH_TOKEN)
  }

  def cleanup() {
    Twilio.getExecutorService().shutdown()
    Twilio.setExecutorService(null)
    Twilio.setRestClient(null)
  }

  abstract String httpClientOperation()

  @Override
  String operation() {
    return "twilio.sdk"
  }

  def "synchronous message"() {
    setup:
    twilioRestClient.getObjectMapper() >> new ObjectMapper()

    1 * twilioRestClient.request(_) >> new Response(new ByteArrayInputStream(MESSAGE_RESPONSE_BODY.getBytes()), 200)

    Message message = runUnderTrace("test") {
      Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).create(twilioRestClient)
    }

    expect:

    message.body == "Hello, World!"

    assertTraces(1) {
      trace(2) {
        span {
          operationName "test"
          resourceName "test"
          errored false
          parent()
          tags {
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  def "synchronous call"() {
    setup:
    twilioRestClient.getObjectMapper() >> new ObjectMapper()

    1 * twilioRestClient.request(_) >> new Response(new ByteArrayInputStream(CALL_RESPONSE_BODY.getBytes()), 200)

    Call call = runUnderTrace("test") {
      Call.creator(
        new PhoneNumber("+15558881234"),  // To number
        new PhoneNumber("+15559994321"),  // From number

        // Read TwiML at this URL when a call connects (hold music)
        new URI("http://twimlets.com/holdmusic?Bucket=com.twilio.music.ambient")
        ).create(twilioRestClient)
    }

    expect:

    call.status == Call.Status.COMPLETED

    assertTraces(1) {
      trace(2) {
        span {
          operationName "test"
          resourceName "test"
          errored false
          parent()
          tags {
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.CallCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Call"
            "twilio.account" "ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.sid" "CAXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "completed"
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }


  def "http client"() {
    setup:
    HttpClientBuilder clientBuilder = Mock()
    CloseableHttpClient httpClient = Mock()
    CloseableHttpResponse httpResponse = Mock()
    HttpEntity httpEntity = Mock()
    StatusLine statusLine = Mock()

    clientBuilder.build() >> httpClient

    httpClient.execute(_) >> httpResponse

    httpResponse.getEntity() >> httpEntity
    httpResponse.getStatusLine() >> statusLine

    httpEntity.getContent() >> { new ByteArrayInputStream(MESSAGE_RESPONSE_BODY.getBytes()) }
    httpEntity.isRepeatable() >> true
    httpEntity.getContentLength() >> MESSAGE_RESPONSE_BODY.length()

    statusLine.getStatusCode() >> HttpStatus.SC_OK

    NetworkHttpClient networkHttpClient = new NetworkHttpClient(clientBuilder)

    TwilioRestClient realTwilioRestClient =
      new TwilioRestClient.Builder("username", "password")
      .accountSid(SHORT_SID)
      .httpClient(networkHttpClient)
      .build()

    Message message = runUnderTrace("test") {
      Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).create(realTwilioRestClient)
    }

    expect:

    message.body == "Hello, World!"

    assertTraces(1) {
      trace(3) {
        span {
          operationName "test"
          resourceName "test"
          errored false
          parent()
          tags {
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
        span {
          serviceName service()
          operationName httpClientOperation()
          resourceName "POST /?/Accounts/abc/Messages.json"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" String
            "$Tags.HTTP_URL" String
            "$Tags.HTTP_METHOD" String
            "$Tags.HTTP_STATUS" Integer
            defaultTags()
          }
        }
      }
    }
  }

  def "http client retry"() {
    setup:
    HttpClientBuilder clientBuilder = Mock()
    CloseableHttpClient httpClient = Mock()
    CloseableHttpResponse httpResponse1 = Mock()
    CloseableHttpResponse httpResponse2 = Mock()
    HttpEntity httpEntity1 = Mock()
    HttpEntity httpEntity2 = Mock()
    StatusLine statusLine1 = Mock()
    StatusLine statusLine2 = Mock()

    clientBuilder.build() >> httpClient

    httpClient.execute(_) >>> [httpResponse1, httpResponse2]

    // First response is an HTTP/500 error, which should drive a retry
    httpResponse1.getEntity() >> httpEntity1
    httpResponse1.getStatusLine() >> statusLine1

    httpEntity1.getContent() >> { new ByteArrayInputStream(ERROR_RESPONSE_BODY.getBytes()) }

    httpEntity1.isRepeatable() >> true
    httpEntity1.getContentLength() >> ERROR_RESPONSE_BODY.length()

    statusLine1.getStatusCode() >> HttpStatus.SC_INTERNAL_SERVER_ERROR

    // Second response is HTTP/200 success
    httpResponse2.getEntity() >> httpEntity2
    httpResponse2.getStatusLine() >> statusLine2

    httpEntity2.getContent() >> {
      new ByteArrayInputStream(MESSAGE_RESPONSE_BODY.getBytes())
    }
    httpEntity2.isRepeatable() >> true
    httpEntity2.getContentLength() >> MESSAGE_RESPONSE_BODY.length()

    statusLine2.getStatusCode() >> HttpStatus.SC_OK

    NetworkHttpClient networkHttpClient = new NetworkHttpClient(clientBuilder)

    TwilioRestClient realTwilioRestClient =
      new TwilioRestClient.Builder("username", "password")
      .accountSid(SHORT_SID)
      .httpClient(networkHttpClient)
      .build()

    Message message = runUnderTrace("test") {
      Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).create(realTwilioRestClient)
    }

    expect:
    message.body == "Hello, World!"

    assertTraces(1) {
      trace(4) {
        span {
          operationName "test"
          resourceName "test"
          errored false
          parent()
          tags {
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
        span {
          serviceName service()
          operationName httpClientOperation()
          resourceName "POST /?/Accounts/abc/Messages.json"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" String
            "$Tags.HTTP_URL" String
            "$Tags.HTTP_METHOD" String
            "$Tags.HTTP_STATUS" Integer
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName httpClientOperation()
          resourceName "POST /?/Accounts/abc/Messages.json"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "api.twilio.com"
            "$Tags.HTTP_URL" "https://api.twilio.com/2010-04-01/Accounts/abc/Messages.json"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 500
            defaultTags()
          }
        }
      }
    }
  }

  def "http client retry async"() {
    setup:
    HttpClientBuilder clientBuilder = Mock()
    CloseableHttpClient httpClient = Mock()
    CloseableHttpResponse httpResponse1 = Mock()
    CloseableHttpResponse httpResponse2 = Mock()
    HttpEntity httpEntity1 = Mock()
    HttpEntity httpEntity2 = Mock()
    StatusLine statusLine1 = Mock()
    StatusLine statusLine2 = Mock()

    clientBuilder.build() >> httpClient

    httpClient.execute(_) >>> [httpResponse1, httpResponse2]

    // First response is an HTTP/500 error, which should drive a retry
    httpResponse1.getEntity() >> httpEntity1
    httpResponse1.getStatusLine() >> statusLine1

    httpEntity1.getContent() >> { new ByteArrayInputStream(ERROR_RESPONSE_BODY.getBytes()) }

    httpEntity1.isRepeatable() >> true
    httpEntity1.getContentLength() >> ERROR_RESPONSE_BODY.length()

    statusLine1.getStatusCode() >> HttpStatus.SC_INTERNAL_SERVER_ERROR

    // Second response is HTTP/200 success
    httpResponse2.getEntity() >> httpEntity2
    httpResponse2.getStatusLine() >> statusLine2

    httpEntity2.getContent() >> {
      new ByteArrayInputStream(MESSAGE_RESPONSE_BODY.getBytes())
    }
    httpEntity2.isRepeatable() >> true
    httpEntity2.getContentLength() >> MESSAGE_RESPONSE_BODY.length()

    statusLine2.getStatusCode() >> HttpStatus.SC_OK

    NetworkHttpClient networkHttpClient = new NetworkHttpClient(clientBuilder)

    TwilioRestClient realTwilioRestClient =
      new TwilioRestClient.Builder("username", "password")
      .accountSid(SHORT_SID)
      .httpClient(networkHttpClient)
      .build()

    Message message = runUnderTrace("test") {
      ListenableFuture<Message> future = Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).createAsync(realTwilioRestClient)

      try {
        return future.get(10, TimeUnit.SECONDS)
      } finally {
        // Give the future callback a chance to run
        Thread.sleep(1000)
      }
    }

    expect:
    message.body == "Hello, World!"

    assertTraces(1) {
      trace(5) {
        span {
          operationName "test"
          resourceName "test"
          errored false
          parent()
          tags {
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.createAsync"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
        // Spans are reported in reverse order of completion,
        // so the error span is last even though it happened first.
        span {
          serviceName service()
          operationName httpClientOperation()
          resourceName "POST /?/Accounts/abc/Messages.json"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" String
            "$Tags.HTTP_URL" String
            "$Tags.HTTP_METHOD" String
            "$Tags.HTTP_STATUS" Integer
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName httpClientOperation()
          resourceName "POST /?/Accounts/abc/Messages.json"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "api.twilio.com"
            "$Tags.HTTP_URL" "https://api.twilio.com/2010-04-01/Accounts/abc/Messages.json"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 500
            defaultTags()
          }
        }
      }
    }

    cleanup:
    Twilio.getExecutorService().shutdown()
    Twilio.setExecutorService(null)
    Twilio.setRestClient(null)
  }

  def "Sync Failure"() {
    setup:

    twilioRestClient.getObjectMapper() >> new ObjectMapper()

    1 * twilioRestClient.request(_) >> new Response(new ByteArrayInputStream(ERROR_RESPONSE_BODY.getBytes()), 500)

    when:
    runUnderTrace("test") {
      Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).create(twilioRestClient)
    }

    then:
    thrown(ApiException)

    expect:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "test"
          resourceName "test"
          errored true
          parent()
          tags {
            errorTags(ApiException, "Testing Failure")
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            errorTags(ApiException, "Testing Failure")
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  def "root span"() {
    setup:
    twilioRestClient.getObjectMapper() >> new ObjectMapper()

    1 * twilioRestClient.request(_) >> new Response(new ByteArrayInputStream(MESSAGE_RESPONSE_BODY.getBytes()), 200)

    Message message = Message.creator(
      new PhoneNumber("+1 555 720 5913"),  // To number
      new PhoneNumber("+1 555 555 5215"),  // From number
      "Hello world!"                    // SMS body
      ).create(twilioRestClient)

    expect:

    message.body == "Hello, World!"

    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  def "asynchronous call"(a) {
    setup:
    twilioRestClient.getObjectMapper() >> new ObjectMapper()

    1 * twilioRestClient.request(_) >> new Response(new ByteArrayInputStream(MESSAGE_RESPONSE_BODY.getBytes()), 200)

    when:

    Message message = runUnderTrace("test") {

      ListenableFuture<Message> future = Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).createAsync(twilioRestClient)

      try {
        return future.get(10, TimeUnit.SECONDS)
      } finally {
        // Give the future callback a chance to run
        Thread.sleep(1000)
      }
    }

    then:

    message != null
    message.body == "Hello, World!"

    assertTraces(1) {
      trace(3) {
        span {
          operationName "test"
          resourceName "test"
          errored false
          parent()
          tags {
            defaultTags()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.createAsync"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "twilio.type" "com.twilio.rest.api.v2010.account.Message"
            "twilio.account" "$LONG_SID"
            "twilio.sid" "MMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            "twilio.status" "sent"
            defaultTagsNoPeerService()
          }
        }
      }
    }

    cleanup:
    Twilio.getExecutorService().shutdown()
    Twilio.setExecutorService(null)
    Twilio.setRestClient(null)

    where:
    a | _
    1 | _
    2 | _
  }

  def "asynchronous error"() {
    setup:
    twilioRestClient.getObjectMapper() >> new ObjectMapper()

    1 * twilioRestClient.request(_) >> new Response(new ByteArrayInputStream(ERROR_RESPONSE_BODY.getBytes()), 500)

    when:
    runUnderTrace("test") {
      ListenableFuture<Message> future = Message.creator(
        new PhoneNumber("+1 555 720 5913"),  // To number
        new PhoneNumber("+1 555 555 5215"),  // From number
        "Hello world!"                    // SMS body
        ).createAsync(twilioRestClient)

      try {
        return future.get(10, TimeUnit.SECONDS)
      } finally {
        Thread.sleep(1000)
      }
    }

    then:
    thrown(ExecutionException)

    expect:

    assertTraces(1) {
      trace(3) {
        span {
          operationName "test"
          resourceName "test"
          errored true
          parent()
          tags {
            defaultTags()
            errorTags(ApiException, "Testing Failure")
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.createAsync"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            errorTags(ApiException, "Testing Failure")
            defaultTagsNoPeerService()
          }
        }
        span {
          serviceName service()
          operationName operation()
          resourceName "api.v2010.account.MessageCreator.create"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "twilio-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            errorTags(ApiException, "Testing Failure")
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }
}

class TwilioClientV0Test extends TwilioClientTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "twilio-sdk"
  }

  @Override
  String httpClientOperation() {
    return new TestingGenericHttpNamingConventions.ClientV0(){}.operation()
  }
}

class TwilioClientV1ForkedTest extends TwilioClientTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return Config.get().getServiceName()
  }

  @Override
  String httpClientOperation() {
    return new TestingGenericHttpNamingConventions.ClientV1(){}.operation()
  }
}
