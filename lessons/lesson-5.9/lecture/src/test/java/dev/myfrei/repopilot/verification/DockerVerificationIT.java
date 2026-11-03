package dev.myfrei.repopilot.verification;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;

/**
 * Dedicated opt-in real Docker tests. No host execution of target Java, POM, plugins or scripts.
 */
@Timeout(60)
class DockerVerificationIT {
  @TempDir Path root;
  WorkspaceService workspace;
  AgentStore store;
  UUID conversation;
  String image;
  String original;

  @BeforeEach
  void prepare() throws Exception {
    image = System.getProperty("verification.image");
    assertThat(image)
        .as("Build verification-runner and pass -Dverification.image=sha256:...")
        .matches("sha256:[0-9a-f]{64}");
    Path file = root.resolve(VerificationService.SOURCE);
    Files.createDirectories(file.getParent());
    original = Files.readString(Path.of("workspace-fixture").resolve(VerificationService.SOURCE));
    Files.writeString(file, original);
    workspace = new WorkspaceService(root);
    store = new AgentStore();
    conversation = store.createConversation().id();
  }

  VerificationService service(VerificationRunner runner) {
    return new VerificationService(workspace, store, runner, image, Clock.systemUTC());
  }

  VerificationReport execute(VerificationService service) {
    var r = service.request(conversation, VerificationService.COMMAND);
    service.decide(r.id(), "APPROVE");
    return service.reports(conversation).getLast();
  }

  @Test
  void actualFailureThenSeparateReadPatchApprovalThenFreshCheckPasses() throws Exception {
    Files.writeString(
        root.resolve(VerificationService.SOURCE),
        original.replace("quantity>available", "quantity>=available"));
    var verifier = service(new DockerVerificationRunner());
    var failed = execute(verifier);
    assertThat(failed.status()).as(failed.stdout() + failed.stderr()).isEqualTo("FAIL");
    assertThat(failed.exitCode()).isEqualTo(10);
    assertThat(failed.stdout()).contains("exact-stock-reservation=FAIL");
    ChatModel model =
        prompt ->
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("Prepare a reviewed patch."),
                        ChatGenerationMetadata.builder().finishReason("stop").build())));
    var engine = new AgentEngine(model, () -> "a".repeat(64), List.of(), store, "fixture", 16384);
    var run = engine.run(conversation, "Fix boundary");
    var read = workspace.readFile(run.id().toString(), VerificationService.SOURCE, 17, 22);
    var controlled = new ControlledService(engine, workspace, Clock.systemUTC());
    var patch =
        controlled.propose(
            new AgentToolContext(run.id(), conversation),
            RunMode.EDIT,
            VerificationService.SOURCE,
            read.fullFileHash(),
            "quantity>=available",
            "quantity>available");
    assertThat(controlled.decide(patch.id(), "APPROVE").status()).isEqualTo("APPLIED");
    var passing = execute(verifier);
    assertThat(passing.status()).as(passing.stdout() + passing.stderr()).isEqualTo("PASS");
    assertThat(passing.exitCode()).isZero();
    assertThat(passing.inputRevision()).isNotEqualTo(failed.inputRevision());
    assertThat(passing.inputsStillCurrent()).isTrue();
    assertThat(verifier.state(conversation).state()).isEqualTo("CHECKED_PASS");
    assertThat(Files.readString(root.resolve(VerificationService.SOURCE))).isEqualTo(original);
  }

  @Test
  void systemExitZeroInTargetChildDoesNotImpersonateSuccessfulTrustedParent() throws Exception {
    Files.writeString(
        root.resolve(VerificationService.SOURCE),
        original.replace(
            "this.stock=new HashMap<>(initial);",
            "if(System.nanoTime()!=0)System.exit(0); this.stock=new HashMap<>(initial);"));
    var report = execute(service(new DockerVerificationRunner()));
    assertThat(report.status()).as(report.stdout() + report.stderr()).isEqualTo("ERROR");
    assertThat(report.exitCode()).isEqualTo(20);
    assertThat(report.stdout()).contains("PROBE_PROTOCOL_ERROR childExit=0");
  }

  @Test
  void actualContainerCannotWriteInputReachNetworkOrSeeSocketButCanUseBuildTmpfs()
      throws Exception {
    String checks =
        """
        try {
          String uid=java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/status"));
          if(!uid.contains("Uid:"+(char)9+"65532"+(char)9+"65532"))throw new IllegalStateException("Wrong uid");
          if(java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"))||java.nio.file.Files.exists(java.nio.file.Path.of("/input/pom.xml")))throw new IllegalStateException("Unexpected host inputs");
          java.nio.file.Files.writeString(java.nio.file.Path.of("/build/allowed"),"ok");
          boolean denied=false;try{java.nio.file.Files.writeString(java.nio.file.Path.of("/input/forbidden"),"bad");}catch(java.io.IOException expected){denied=true;}if(!denied)throw new IllegalStateException("Input writable");
          boolean offline=false;try(var socket=new java.net.Socket()){try{socket.connect(new java.net.InetSocketAddress("1.1.1.1",80),300);}catch(java.io.IOException expected){offline=true;}}if(!offline)throw new IllegalStateException("Network available");
        }catch(java.io.IOException bad){throw new IllegalStateException(bad);}
        """;
    Files.writeString(
        root.resolve(VerificationService.SOURCE),
        original.replace(
            "this.stock=new HashMap<>(initial);", checks + "this.stock=new HashMap<>(initial);"));
    Files.writeString(root.resolve("pom.xml"), "UNTRUSTED_POM_MUST_NOT_BE_COPIED_OR_RUN");
    var report = execute(service(new DockerVerificationRunner()));
    assertThat(report.status()).as(report.stdout() + report.stderr()).isEqualTo("PASS");
    assertThat(Files.exists(root.resolve("forbidden"))).isFalse();
  }

  @Test
  void timeoutRemovesTheExactOwnedContainerInsteadOfOnlyKillingDockerClient() throws Exception {
    Files.writeString(
        root.resolve(VerificationService.SOURCE),
        original.replace(
            "this.stock=new HashMap<>(initial);",
            "while(System.nanoTime()!=0){} this.stock=new HashMap<>(initial);"));
    var name = new AtomicReference<String>();
    var actual = new BoundedCommand();
    var runner =
        new DockerVerificationRunner(
            (args, t, b) -> {
              if (args.contains("create")) name.set(args.get(args.indexOf("--name") + 1));
              return actual.execute(args, t, b);
            },
            Duration.ofSeconds(1));
    var report = execute(service(runner));
    assertThat(report.status()).as(report.stdout() + report.stderr()).isEqualTo("TIMEOUT");
    assertThat(name.get()).isNotNull();
    var inspect =
        actual.execute(List.of("docker", "inspect", name.get()), Duration.ofSeconds(5), 1024);
    assertThat(inspect.exitCode()).isNotEqualTo(0);
  }

  @Test
  void noisyTargetCannotExceedBoundedOutputAndTruncationIsVisible() throws Exception {
    Files.writeString(
        root.resolve(VerificationService.SOURCE),
        original.replace(
            "this.stock=new HashMap<>(initial);",
            "System.err.println(\"noise\".repeat(10000)); this.stock=new HashMap<>(initial);"));
    var report = execute(service(new DockerVerificationRunner()));
    assertThat(report.status()).as(report.reason()).isEqualTo("PASS");
    assertThat(report.outputTruncated()).isTrue();
    assertThat(AgentJson.bytes(report.stderr())).isLessThanOrEqualTo(8192);
    assertThat(report.stderr()).contains("PROBE_OUTPUT_TRUNCATED");
  }

  @Test
  void stalePermissionAndReplayNeverRunASecondContainer() throws Exception {
    var calls = new AtomicInteger();
    var real = new DockerVerificationRunner();
    var verifier =
        service(
            (id, path) -> {
              calls.incrementAndGet();
              return real.run(id, path);
            });
    var stale = verifier.request(conversation, VerificationService.COMMAND);
    Files.writeString(root.resolve(VerificationService.SOURCE), original + "\n// human changed\n");
    assertThatThrownBy(() -> verifier.decide(stale.id(), "APPROVE"))
        .isInstanceOfSatisfying(
            VerificationProblem.class, e -> assertThat(e.code()).isEqualTo("VERIFICATION_STALE"));
    assertThat(calls).hasValue(0);
    var request = verifier.request(conversation, VerificationService.COMMAND);
    assertThat(verifier.decide(request.id(), "APPROVE").status()).isEqualTo("PASS");
    assertThatThrownBy(() -> verifier.decide(request.id(), "APPROVE"))
        .isInstanceOf(VerificationProblem.class);
    assertThat(calls).hasValue(1);
  }
}
