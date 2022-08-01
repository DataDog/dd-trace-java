package datadog.communication.http

import datadog.communication.http.SafeRequestBuilder
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.Test
import org.junit.Assert

class SafeRequestBuilderTest {
  SafeRequestBuilder.Builder testBuilder = new SafeRequestBuilder.Builder()

  @Test
  void "test add header"(){
    testBuilder.url("http:localhost").addHeader("test","test")
    Assert.assertEquals(testBuilder.build().headers().get("test"),"test")
  }
  @Test(expected = IllegalArgumentException.class)
  void "test adding bad header"(){
    testBuilder.addHeader("\n\n","\n\n")
  }
  @Test (expected = IllegalArgumentException.class)
  void "test adding bad header2"(){
    testBuilder.url("localhost").header("\n\n\n","\n\n\n")
  }
  @Test
  void "test building result"(){
    Request testRequest = new SafeRequestBuilder.Builder().url("http://localhost")
      .header("key","value").build()
    Assert.assertEquals(Request.class,testRequest.getClass())
  }
  @Test
  void "test getBuilder"(){
    Request.Builder originalBuilder = new Request.Builder().url("http://localhost")
      .addHeader("test","test")
    testBuilder = new SafeRequestBuilder.Builder(originalBuilder)
    Assert.assertEquals(originalBuilder,testBuilder.getBuilder())
  }
  @Test
  void "test get method"(){
    testBuilder = new SafeRequestBuilder.Builder().url("http://localhost")
    testBuilder.get()
    testBuilder.headers(new Headers())
    Assert.assertEquals(testBuilder.getClass(),SafeRequestBuilder.Builder.class)
  }
}
