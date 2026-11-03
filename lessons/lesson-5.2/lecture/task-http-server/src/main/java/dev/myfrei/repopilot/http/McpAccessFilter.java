package dev.myfrei.repopilot.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local teaching boundary: shared bearer token plus exact browser Origin allowlist. */
public final class McpAccessFilter extends OncePerRequestFilter {
    public static final Set<String> ALLOWED_ORIGINS=Set.of("http://localhost:8080","http://127.0.0.1:8080");
    private final byte[] expectedToken;
    public McpAccessFilter(String token) {
        if(token==null||token.isBlank()||token.length()>256||token.contains("\r")||token.contains("\n"))throw new IllegalArgumentException("Configure a nonblank bounded TaskShelf token");
        this.expectedToken=token.getBytes(StandardCharsets.UTF_8);
    }
    // Dedicated MCP application: guard every route, including normalized/matrix-path variants.
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var origins=Collections.list(request.getHeaders("Origin"));
        if(origins.size()>1||(!origins.isEmpty()&&!ALLOWED_ORIGINS.contains(origins.getFirst()))) {deny(response,403,"INVALID_ORIGIN");return;}
        var headers=Collections.list(request.getHeaders("Authorization"));
        String header=headers.size()==1?headers.getFirst():null;
        if(header==null||header.length()<7||!header.regionMatches(true,0,"Bearer ",0,7)
                ||!MessageDigest.isEqual(this.expectedToken,header.substring(7).getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate","Bearer realm=\"taskshelf-local\"");deny(response,401,"UNAUTHORIZED");return;
        }
        chain.doFilter(request,response);
    }
    private static void deny(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\""+code+"\"}");
    }
}
