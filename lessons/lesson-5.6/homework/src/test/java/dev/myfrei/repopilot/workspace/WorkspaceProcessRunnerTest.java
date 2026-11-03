package dev.myfrei.repopilot.workspace;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Timeout(8)
class WorkspaceProcessRunnerTest {
    @TempDir Path root;
    private List<String> command(String mode)throws Exception{return List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",Path.of(WorkspaceProcessRunnerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),Child.class.getName(),mode);}
    @Test void hungProcessIsKilledByDeadlineAndCannotWriteItsLateMarker()throws Exception{
        long start=System.nanoTime();assertThatThrownBy(()->new WorkspaceProcessRunner(Duration.ofMillis(250),1024).run(command("slow"),root)).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("GIT_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));assertThat(Files.exists(root.resolve("late-marker"))).isFalse();
    }
    @Test void noisyStdoutAndStderrAreDrainedWithoutDeadlockAndBytesAreBounded()throws Exception{
        var result=new WorkspaceProcessRunner(Duration.ofSeconds(3),1024).run(command("noisy"),root);assertThat(result.exitCode()).isZero();assertThat(result.bytes()).hasSize(1024);assertThat(result.truncated()).isTrue();assertThat(new String(result.bytes(),StandardCharsets.UTF_8)).startsWith("data");
    }
    @Test void missingExecutableIsSanitizedUnavailable(){assertThatThrownBy(()->new WorkspaceProcessRunner().run(List.of(root.resolve("does-not-exist").toString()),root)).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("GIT_UNAVAILABLE"));}
    public static class Child {
        public static void main(String[] args)throws Exception{
            if(args[0].equals("slow")){Thread.sleep(5000);Files.writeString(Path.of("late-marker"),"must not happen");}
            else{for(int i=0;i<3000;i++){System.out.print("data".repeat(10));System.err.print("error".repeat(10));}}
        }
    }
}
