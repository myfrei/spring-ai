package dev.myfrei.repopilot.mcphost;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
/** Test-only process lifecycle. Never builds artifacts or invokes Maven. */
public final class PackagedTaskServer implements AutoCloseable {
 private Process process;private Path log;private URI base;
 public PackagedTaskServer(){try{
  String configured=System.getProperty("taskshelf.http.jar","");if(configured.isBlank())throw new IllegalArgumentException("Build lesson4.3/task-http-server first and pass -Dtaskshelf.http.jar=/absolute/server.jar");
  var jar=Path.of(configured).toAbsolutePath();if(!Files.isRegularFile(jar))throw new IllegalArgumentException("Packaged MCP server JAR not found");
  log=Files.createTempFile("taskshelf-real-host-it-",".log");process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),"--server.port=0","--taskshelf.access-token="+McpWireFixture.TOKEN).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  var pattern=Pattern.compile("Tomcat started on port (\\d+) ");long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
  while(System.nanoTime()<deadline){String text=Files.readString(log);var match=pattern.matcher(text);if(match.find()){base=URI.create("http://127.0.0.1:"+match.group(1));return;}if(!process.isAlive())throw new IllegalStateException("Packaged MCP server exited: "+text);Thread.sleep(25);}
  throw new IllegalStateException("Packaged MCP server did not start");
 }catch(Exception error){close();throw new IllegalStateException("Cannot start packaged lesson4.3 MCP HTTP server",error);}}
 public URI base(){return base;}
 @Override public void close(){try{if(process!=null){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}if(log!=null)Files.deleteIfExists(log);}catch(Exception error){throw new IllegalStateException("Cannot stop packaged MCP server",error);}}
}
