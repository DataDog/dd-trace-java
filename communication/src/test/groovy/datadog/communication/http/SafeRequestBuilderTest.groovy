package datadog.communication.http


import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.Test
import org.junit.Assert

class SafeRequestBuilderTest {
  SafeRequestBuilder testBuilder = new SafeRequestBuilder()

  @Test
  void "test add header"(){
    testBuilder.url("http:localhost").addHeader("test","test")
    Assert.assertEquals(testBuilder.build().headers().get("test"),"test")
  }
  @Test
  void "test remove header"(){
    testBuilder.url("http:localhost").removeHeader("test")
    Assert.assertEquals(testBuilder.build().headers().get("test"),null)
  }
  @Test
  void "test static add header"(){
    Request.Builder builder = new Request.Builder().url("http://localhost")
    builder = SafeRequestBuilder.addHeader(builder,"test","test")
    Assert.assertEquals(builder.build().headers().get("test"),"test")
  }
  @Test (expected = IllegalArgumentException)
  void "test bad static add header"(){
    Request.Builder builder = new Request.Builder().url("http://localhost")
    builder = SafeRequestBuilder.addHeader(builder,"\n\n","\n\n")
  }
  @Test(expected = IllegalArgumentException)
  void "test adding bad header"(){
    testBuilder.url("http:localhost").addHeader("\n\n","\n\n")
  }
  @Test (expected = IllegalArgumentException)
  void "test adding bad header2"(){
    testBuilder.url("localhost").header("\u0019","\u0080")
  }
  @Test
  void "test building result"(){
    Request testRequest = new SafeRequestBuilder().url("http://localhost")
      .header("key","value").build()
    Assert.assertEquals(Request,testRequest.getClass())
  }
  @Test
  void "test getBuilder"(){
    Request.Builder originalBuilder = new Request.Builder().url("http://localhost")
      .addHeader("test","test")
    testBuilder = new SafeRequestBuilder(originalBuilder)
    Assert.assertEquals(originalBuilder,testBuilder.getBuilder())
  }
  @Test
  void "test get method"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    testBuilder.get()
    testBuilder.headers(new Headers())
    Assert.assertEquals(testBuilder.build().method(),"GET")
  }
  @Test
  void "test post method"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{}")
    testBuilder.post(body)
    Assert.assertEquals(testBuilder.build().method(),"POST")
  }
  @Test
  void "test put method"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{}")
    testBuilder.put(body)
    Assert.assertEquals(testBuilder.build().method(),"PUT")
  }
  @Test
  void "test patch method"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{}")
    testBuilder.patch(body)
    Assert.assertEquals(testBuilder.build().method(),"PATCH")
  }
  @Test
  void "test delete method"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    testBuilder.delete()
    Assert.assertEquals(testBuilder.build().method(),"DELETE")
  }
  @Test
  void "test method adder"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    testBuilder.method("GET",null)
  }
  @Test
  void "test tag"(){
    testBuilder = new SafeRequestBuilder()
    URL url = new URL("http://localhost")
    testBuilder.url(url).tag("test")
    Assert.assertEquals(testBuilder.build().tag().toString(),"test")
  }
  @Test
  void "test head"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    testBuilder.head()
    Assert.assertEquals(testBuilder.build().method(),"HEAD")
  }
  @Test
  void "test delete with parameter method"(){
    testBuilder = new SafeRequestBuilder().url("http://localhost")
    RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{}")
    testBuilder.delete(body)
    Assert.assertEquals(testBuilder.build().method(),"DELETE")
  }
}
