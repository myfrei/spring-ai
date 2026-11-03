package dev.myfrei.repopilot.circuit;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.myfrei.repopilot.mcphost.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual Spring HTTP child process + native SDK session; no fake remote boundary. */
class CircuitGatewayIT {
    @Test void outageOpensCircuitAndSingleProbeReconnectsAfterServerRestart() throws Exception {
        String configured=System.getProperty("taskshelf.http.jar");
        assertThat(configured).as("Pass -Dtaskshelf.http.jar=/absolute/path/to/built/http-server.jar").isNotBlank();
        var jar=Path.of(configured).toAbsolutePath();assertThat(jar).isRegularFile();
        int port;try(var reservation=new java.net.ServerSocket(0,50,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
        var logs=Files.createTempFile("circuit-http-it-",".log");Process process=null;
        var clock=new TestClock();var gate=new CircuitGate(3,Duration.ofSeconds(30),clock);var attempts=new AtomicInteger();
        try(var nativeGateway=new LazyMcpTaskGateway(URI.create("http://127.0.0.1:"+port),"course-local-token",Duration.ofSeconds(1))){
            process=start(jar,port,logs);awaitReady(process,port,logs);
            var decorated=new CircuitTaskGateway(id->{attempts.incrementAndGet();return nativeGateway.readTask(id);},gate);
            var first=decorated.readTask("TASK-101");assertThat(first.task().id()).isEqualTo("TASK-101");assertThat(first.metadata().remoteCalls()).isEqualTo(1);
            stop(process);process=null;
            for(int i=0;i<3;i++)assertThatThrownBy(()->decorated.readTask("TASK-101")).isInstanceOfSatisfying(GatewayException.class,error->assertThat(error.kind()).isEqualTo(GatewayException.Kind.AVAILABILITY));
            assertThat(gate.snapshot().state()).isEqualTo(CircuitGate.State.OPEN);assertThat(attempts).hasValue(4);
            assertBlocked(decorated);assertThat(attempts).hasValue(4);
            process=start(jar,port,logs);awaitReady(process,port,logs);clock.advance(29);assertBlocked(decorated);assertThat(attempts).hasValue(4);
            clock.advance(1);var recovered=decorated.readTask("TASK-101");
            assertThat(recovered.task().id()).isEqualTo("TASK-101");assertThat(recovered.metadata().initializations()).isEqualTo(1);assertThat(recovered.metadata().discoveries()).isEqualTo(1);
            assertThat(recovered.metadata().remoteCalls()).isEqualTo(1);assertThat(attempts).hasValue(5);assertThat(gate.snapshot().state()).isEqualTo(CircuitGate.State.CLOSED);
        }finally{stop(process);Files.deleteIfExists(logs);}
    }
    private static void assertBlocked(RemoteTaskGateway gateway){assertThatThrownBy(()->gateway.readTask("TASK-101")).isInstanceOfSatisfying(GatewayException.class,error->{assertThat(error.code()).isEqualTo("MCP_CIRCUIT_OPEN");assertThat(error.metadata().remoteCalls()).isZero();assertThat(error.metadata().initializations()).isZero();});}
    private static Process start(Path jar,int port,Path log) throws IOException{return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),"--server.port="+port,"--taskshelf.access-token=course-local-token").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();}
    private static void awaitReady(Process process,int port,Path logs) throws Exception{
        var http=HttpClient.newHttpClient();var until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<until){if(!process.isAlive())throw new AssertionError("Server exited: "+Files.readString(logs));
            try{var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp")).timeout(Duration.ofMillis(250)).GET().build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()==401)return;}catch(IOException ignored){}
            Thread.sleep(50);
        }throw new AssertionError("Server not ready: "+Files.readString(logs));
    }
    private static void stop(Process process) throws InterruptedException{if(process==null)return;process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    private static class TestClock extends Clock{
        Instant now=Instant.parse("2026-10-31T00:00:00Z");void advance(long seconds){now=now.plusSeconds(seconds);}
        public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}
    }
}
