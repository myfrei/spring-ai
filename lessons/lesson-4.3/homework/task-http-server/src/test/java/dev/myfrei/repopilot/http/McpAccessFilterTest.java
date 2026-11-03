package dev.myfrei.repopilot.http;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class McpAccessFilterTest {
    private static final String TOKEN="local-test-token";
    private MockHttpServletResponse request(String method,String origin,String auth,AtomicInteger invoked) throws Exception {
        var request=new MockHttpServletRequest(method,"/mcp");if(origin!=null)request.addHeader("Origin",origin);if(auth!=null)request.addHeader("Authorization",auth);
        var response=new MockHttpServletResponse();new McpAccessFilter(TOKEN).doFilter(request,response,(req,res)->invoked.incrementAndGet());return response;
    }
    @Test void everyMcpMethodRequiresBearerAndUnauthorizedResponseEchoesNoInput() throws Exception {
        for(String method:new String[]{"POST","GET","DELETE","OPTIONS","HEAD","PATCH"}) {
            var called=new AtomicInteger();var response=request(method,null,null,called);assertThat(response.getStatus()).isEqualTo(401);assertThat(called).hasValue(0);assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
            assertThat(response.getContentAsString()).contains("UNAUTHORIZED").doesNotContain(TOKEN);
        }
    }
    @Test void validBearerWithoutOriginIsAllowedForNativeSdk() throws Exception {
        var called=new AtomicInteger();request("POST",null,"Bearer "+TOKEN,called);assertThat(called).hasValue(1);
        request("DELETE",null,"bearer "+TOKEN,called);assertThat(called).hasValue(2);
    }
    @Test void onlyExactTwoBrowserOriginsAreAllowed() throws Exception {
        for(String origin:McpAccessFilter.ALLOWED_ORIGINS) {var called=new AtomicInteger();request("POST",origin,"Bearer "+TOKEN,called);assertThat(called).hasValue(1);}
        for(String origin:new String[]{"http://evil.example","null","","http://localhost:8080/","http://localhost:8092","https://localhost:8080"}) {
            var called=new AtomicInteger();var response=request("POST",origin,"Bearer "+TOKEN,called);assertThat(response.getStatus()).isEqualTo(403);assertThat(called).hasValue(0);assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"INVALID_ORIGIN\"}");
        }
    }
    @Test void invalidOriginHas403EvenWhenAuthorizationIsMissing() throws Exception {
        var called=new AtomicInteger();assertThat(request("GET","http://evil.example",null,called).getStatus()).isEqualTo(403);assertThat(called).hasValue(0);
    }
    @Test void duplicateHeadersAndWrongSchemeOrTokenAreDenied() throws Exception {
        var filter=new McpAccessFilter(TOKEN);var request=new MockHttpServletRequest("POST","/mcp");request.addHeader("Authorization","Bearer "+TOKEN);request.addHeader("Authorization","Bearer "+TOKEN);
        var response=new MockHttpServletResponse();var called=new AtomicInteger();filter.doFilter(request,response,(req,res)->called.incrementAndGet());assertThat(response.getStatus()).isEqualTo(401);assertThat(called).hasValue(0);
        request=new MockHttpServletRequest("POST","/mcp");request.addHeader("Authorization","Bearer "+TOKEN);request.addHeader("Origin","http://localhost:8080");request.addHeader("Origin","http://evil.example");response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->called.incrementAndGet());assertThat(response.getStatus()).isEqualTo(403);
        for(String auth:new String[]{"Basic "+TOKEN,"Bearer incorrect","Bearer  "+TOKEN,"Bearer "+TOKEN.toUpperCase()})assertThat(request("POST",null,auth,new AtomicInteger()).getStatus()).isEqualTo(401);
    }
    @Test void allDedicatedServerPathsAreProtectedAndInvalidConfiguredTokenFailsSafely() throws Exception {
        var request=new MockHttpServletRequest("GET","/unrelated");var called=new AtomicInteger();new McpAccessFilter(TOKEN).doFilter(request,new MockHttpServletResponse(),(req,res)->called.incrementAndGet());assertThat(called).hasValue(0);
        assertThatIllegalArgumentException().isThrownBy(()->new McpAccessFilter(" "));assertThatIllegalArgumentException().isThrownBy(()->new McpAccessFilter("x".repeat(257)));
    }
}
