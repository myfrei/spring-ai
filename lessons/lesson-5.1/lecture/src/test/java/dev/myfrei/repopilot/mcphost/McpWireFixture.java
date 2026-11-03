package dev.myfrei.repopilot.mcphost;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import tools.jackson.databind.json.JsonMapper;

/** Fault-injectable wire peer; the host uses the unmodified native MCP SDK client. */
public final class McpWireFixture implements AutoCloseable {
 public static final String TOKEN="course-local-token";
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final HttpServer server;private final ExecutorService executor=Executors.newCachedThreadPool();
 public final AtomicReference<String> mode=new AtomicReference<>("VALID");
 public final AtomicInteger initializes=new AtomicInteger(),discoveries=new AtomicInteger(),calls=new AtomicInteger();
 public final List<String> headers=new CopyOnWriteArrayList<>();
 public McpWireFixture(){try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/mcp",this::handle);server.start();}catch(IOException e){throw new UncheckedIOException(e);}}
 public URI base(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
 @SuppressWarnings("unchecked")private void handle(HttpExchange x)throws IOException{
  try{
   headers.add(x.getRequestMethod()+":"+x.getRequestHeaders().getFirst("Authorization"));
   if(!("Bearer "+TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))){send(x,401,null);return;}
   if(x.getRequestMethod().equals("GET")){send(x,405,null);return;}
   if(x.getRequestMethod().equals("DELETE")){send(x,200,null);return;}
   var rpc=JSON.readValue(x.getRequestBody().readAllBytes(),Map.class);String method=(String)rpc.get("method");Object id=rpc.get("id");
   if(id==null){send(x,202,null);return;}
   Object result;
   if(method.equals("initialize")){
    initializes.incrementAndGet();x.getResponseHeaders().set("Mcp-Session-Id","fixture-"+initializes.get());
    result=Map.of("protocolVersion","2025-11-25","capabilities",Map.of("tools",Map.of("listChanged",false)),"serverInfo",Map.of("name","taskshelf","version","4.3"));
   }else if(method.equals("tools/list")){
    discoveries.incrementAndGet();var schema=new LinkedHashMap<String,Object>();schema.put("type","object");schema.put("properties",Map.of("taskId",Map.of("type","string")));schema.put("required",List.of("taskId"));schema.put("additionalProperties",false);
    if(mode.get().equals("BAD_SCHEMA"))schema.put("additionalProperties",true);
    result=Map.of("tools",List.of(Map.of("name","getTask","description","Public task","inputSchema",schema,"annotations",Map.of("readOnlyHint",true,"destructiveHint",false)),Map.of("name","notImported","inputSchema",Map.of("type","object"))));
   }else if(method.equals("tools/call")){
    calls.incrementAndGet();String current=mode.get();
    if(current.equals("SESSION_LOST")){mode.set("VALID");send(x,404,null);return;}
    if(current.equals("UNAVAILABLE")){send(x,503,null);return;}
    var params=(Map<?,?>)rpc.get("params");var args=(Map<?,?>)params.get("arguments");String taskId=(String)args.get("taskId");
    var task=new TaskCatalog().findTask(taskId).orElseThrow().toView();var map=JSON.readValue(JSON.writeValueAsString(task),Map.class);
    if(current.equals("WRONG_ID"))map.put("id","TASK-102");
    if(current.equals("REMOTE_ERROR")){result=Map.of("isError",true,"content",List.of(Map.of("type","text","text","PRIVATE-REMOTE-FAILURE")));}
    else{var structured=new LinkedHashMap<>(map);if(current.equals("INCONSISTENT"))structured.put("summary","different");result=Map.of("isError",false,"content",List.of(Map.of("type","text","text",JSON.writeValueAsString(map))),"structuredContent",structured);}
   }else{send(x,200,Map.of("jsonrpc","2.0","id",id,"error",Map.of("code",-32601,"message","Unknown method")));return;}
   send(x,200,Map.of("jsonrpc","2.0","id",id,"result",result));
  }catch(RuntimeException e){send(x,500,null);}
 }
 private static void send(HttpExchange x,int status,Object body)throws IOException{if(body==null){x.sendResponseHeaders(status,-1);}else{byte[]bytes=JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}x.close();}
 @Override public void close(){server.stop(0);executor.shutdownNow();}
}
