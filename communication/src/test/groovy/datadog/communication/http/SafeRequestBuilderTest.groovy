package datadog.communication.http


import okhttp3.Headers
import okhttp3.Request
import org.junit.Test
import org.junit.Assert

class SafeRequestBuilderTest {
  SafeRequestBuilder testBuilder = new SafeRequestBuilder()

  @Test
  void "test add header"(){
    testBuilder.url("http:localhost").addHeader("test","test")
    Assert.assertEquals(testBuilder.build().headers().get("test"),"test")
  }
  @Test(expected = IllegalArgumentException)
  void "test adding bad header"(){
    testBuilder.addHeader("\n\n","\n\n")
  }
  @Test (expected = IllegalArgumentException)
  void "test adding bad header2"(){
    testBuilder.url("localhost").header("\n\n\n","\n\n\n")
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
    Assert.assertEquals(testBuilder.getClass(),SafeRequestBuilder)
  }
}
