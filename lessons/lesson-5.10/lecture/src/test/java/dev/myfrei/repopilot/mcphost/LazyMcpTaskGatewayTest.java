package dev.myfrei.repopilot.mcphost;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@Timeout(15)
class LazyMcpTaskGatewayTest {
 private LazyMcpTaskGateway gateway(McpWireFixture wire){return new LazyMcpTaskGateway(wire.base(),McpWireFixture.TOKEN,Duration.ofSeconds(2));}
 @Test void nativeSdkOneRemoteRpcCachesSessionAndDiscoveryAndSendsBearerOnEveryMethod(){
  try(var wire=new McpWireFixture();var gateway=gateway(wire)){
   assertThat(wire.initializes).hasValue(0);var first=gateway.readTask("TASK-101");var next=gateway.readTask("TASK-102");
   assertThat(first.task().id()).isEqualTo("TASK-101");assertThat(first.metadata().initializations()).isOne();assertThat(first.metadata().discoveries()).isOne();assertThat(first.metadata().remoteCalls()).isOne();
   assertThat(next.metadata().initializations()).isZero();assertThat(next.metadata().discoveries()).isZero();assertThat(next.metadata().remoteCalls()).isOne();assertThat(next.metadata().protocolVersion()).isEqualTo("2025-11-25");
   assertThat(wire.initializes).hasValue(1);assertThat(wire.discoveries).hasValue(1);assertThat(wire.calls).hasValue(2);assertThat(wire.headers).allMatch(h->h.endsWith("Bearer "+McpWireFixture.TOKEN));
  }
 }
 @Test void sessionLossFailsCurrentRequestAndOnlyNextReadReinitializes(){
  try(var wire=new McpWireFixture();var gateway=gateway(wire)){
   gateway.readTask("TASK-101");wire.mode.set("SESSION_LOST");
   assertThatThrownBy(()->gateway.readTask("TASK-101")).isInstanceOf(GatewayException.class).satisfies(e->{var p=(GatewayException)e;assertThat(p.kind()).isEqualTo(GatewayException.Kind.AVAILABILITY);assertThat(p.metadata().initializations()).isZero();assertThat(p.metadata().remoteCalls()).isOne();});
   assertThat(wire.calls).hasValue(2);assertThat(wire.initializes).hasValue(1);
   assertThat(gateway.readTask("TASK-101").metadata().initializations()).isOne();assertThat(wire.calls).hasValue(3);assertThat(wire.initializes).hasValue(2);assertThat(wire.discoveries).hasValue(2);
  }
 }
 @Test void businessAndValidationFailuresDoNotPoisonHealthySessionOrBecomeSuccess(){
  try(var wire=new McpWireFixture();var gateway=gateway(wire)){
   for(String mode:new String[]{"REMOTE_ERROR","WRONG_ID","INCONSISTENT"}){wire.mode.set(mode);assertThatThrownBy(()->gateway.readTask("TASK-101")).isInstanceOf(GatewayException.class).satisfies(e->{var p=(GatewayException)e;assertThat(p.kind()).isEqualTo(mode.equals("REMOTE_ERROR")?GatewayException.Kind.REMOTE_TOOL:GatewayException.Kind.INVALID_RESPONSE);assertThat(p.getMessage()).doesNotContain("PRIVATE");});}
   wire.mode.set("VALID");assertThat(gateway.readTask("TASK-101").metadata().initializations()).isZero();assertThat(wire.initializes).hasValue(1);assertThat(wire.calls).hasValue(4);
  }
 }
 @Test void discoveryAndAuthorizationRejectBeforeToolRpcAndUnknownIdBeforeAnyNetwork(){
  try(var wire=new McpWireFixture();var gateway=gateway(wire)){
   assertThatThrownBy(()->gateway.readTask("TASK-999")).isInstanceOf(GatewayException.class).extracting("kind").isEqualTo(GatewayException.Kind.INVALID_ARGUMENTS);assertThat(wire.headers).isEmpty();
   wire.mode.set("BAD_SCHEMA");assertThatThrownBy(()->gateway.readTask("TASK-101")).isInstanceOf(GatewayException.class).extracting("kind").isEqualTo(GatewayException.Kind.PROTOCOL);assertThat(wire.calls).hasValue(0);
   wire.mode.set("VALID");assertThat(gateway.readTask("TASK-101").metadata().initializations()).isOne();
   try(var wrong=new LazyMcpTaskGateway(wire.base(),"wrong-token",Duration.ofSeconds(1))){assertThatThrownBy(()->wrong.readTask("TASK-101")).isInstanceOf(GatewayException.class).extracting("kind").isEqualTo(GatewayException.Kind.AUTHORIZATION);}
  }
 }
 @Test void constructionIsOfflineAndUrlCannotBecomeArbitraryNetworkDestination(){
  try(var offline=new LazyMcpTaskGateway(URI.create("http://127.0.0.1:1"),"token",Duration.ofMillis(200))){assertThatThrownBy(()->offline.readTask("TASK-101")).isInstanceOf(GatewayException.class).satisfies(e->{assertThat(((GatewayException)e).kind()).isEqualTo(GatewayException.Kind.AVAILABILITY);assertThat(((GatewayException)e).metadata().initializations()).isOne();assertThat(((GatewayException)e).metadata().remoteCalls()).isZero();});}
  for(String uri:new String[]{"https://127.0.0.1:8092","http://localhost:8092","http://example.com:8092","http://127.0.0.1:8092/other","http://user@127.0.0.1:8092"})assertThatIllegalArgumentException().isThrownBy(()->new LazyMcpTaskGateway(URI.create(uri),"token",Duration.ofSeconds(1)));
 }
}
