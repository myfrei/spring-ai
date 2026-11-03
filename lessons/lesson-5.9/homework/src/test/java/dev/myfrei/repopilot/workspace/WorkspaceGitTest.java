package dev.myfrei.repopilot.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class WorkspaceGitTest {
    @TempDir Path root;
    private void git(String... args)throws Exception{
        var command=new ArrayList<>(List.of("git","-C",root.toString(),"-c","user.name=Course Fixture","-c","user.email=course@example.invalid"));command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(output).isZero();
    }
    private Path file(String path,String text)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,text);return target;}
    private void initialized()throws Exception{file("README.md","baseline\n");file("pom.xml","<project/>\n");file("src/A.java","class A { int value=1; }\n");git("init","--quiet");git("add","--","README.md","pom.xml","src/A.java");git("commit","--quiet","-m","Fixture baseline");}
    private void rejected(String code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo(code));}
    @Test void absentGitIsTypedAndDoesNotInitializeAnything()throws Exception{
        file("README.md","not a repository");var service=new WorkspaceService(root);rejected("NO_GIT_REPOSITORY",service::gitStatus);rejected("NO_GIT_REPOSITORY",()->service.gitDiff("README.md","WORKTREE"));assertThat(Files.exists(root.resolve(".git"))).isFalse();
    }
    @Test void statusIncludesTrackedUntrackedAndRenameWithoutLeakingForbiddenNamesOrChangingIndex()throws Exception{
        initialized();Files.move(root.resolve("src/A.java"),root.resolve("src/Renamed.java"));git("add","-A");file("README.md","changed\n");file("src/New.java","class New {}\n");file("src/credentials.txt","PRIVATE-BODY\n");
        Path index=root.resolve(".git/index");byte[] before=Files.readAllBytes(index);var stamp=Files.getLastModifiedTime(index);var result=new WorkspaceService(root).gitStatus();
        assertThat(result.changes()).anySatisfy(change->{assertThat(change.index()).isEqualTo("R");assertThat(change.path()).isEqualTo("src/Renamed.java");assertThat(change.originalPath()).isEqualTo("src/A.java");});
        assertThat(result.changes()).anySatisfy(change->{assertThat(change.worktree()).isEqualTo("M");assertThat(change.path()).isEqualTo("README.md");});
        assertThat(result.changes()).anySatisfy(change->{assertThat(change.index()).isEqualTo("?");assertThat(change.path()).isEqualTo("src/New.java");});
        assertThat(result.omittedChanges()).isEqualTo(1);assertThat(result.truncated()).isTrue();assertThat(WorkspaceJson.encode(result)).doesNotContain("credentials","PRIVATE-BODY");assertThat(Files.readAllBytes(index)).isEqualTo(before);assertThat(Files.getLastModifiedTime(index)).isEqualTo(stamp);
    }
    @Test void diffIsPerFileAndSeparatesStagedFromWorktree()throws Exception{
        initialized();file("src/A.java","class A { int value=2; }\n");git("add","--","src/A.java");file("src/A.java","class A { int value=3; }\n");file("README.md","UNRELATED-PRIVATE\n");var service=new WorkspaceService(root);
        var staged=service.gitDiff("src/A.java","STAGED");assertThat(staged.patch()).contains("-class A { int value=1; }","+class A { int value=2; }").doesNotContain("value=3","UNRELATED-PRIVATE");
        var current=service.gitDiff("src/A.java","WORKTREE");assertThat(current.patch()).contains("-class A { int value=2; }","+class A { int value=3; }").doesNotContain("value=1","UNRELATED-PRIVATE");assertThat(current.truncated()).isFalse();
        file("src/Untracked.java","untracked");rejected("NOT_TRACKED",()->service.gitDiff("src/Untracked.java","WORKTREE"));rejected("NOT_TRACKED",()->service.gitDiff("src/Missing.java","WORKTREE"));
        rejected("PATH_NOT_ALLOWED",()->service.gitDiff("src/*.java","WORKTREE"));rejected("INVALID_DIFF_SCOPE",()->service.gitDiff("src/A.java","HEAD~9"));
    }
    @Test void deletionIsReadableAsPatchButForbiddenAndSymlinkDiffsAreDenied()throws Exception{
        initialized();Files.delete(root.resolve("src/A.java"));var service=new WorkspaceService(root);assertThat(service.gitDiff("src/A.java","WORKTREE").patch()).contains("-class A { int value=1; }");
        rejected("PATH_NOT_ALLOWED",()->service.gitDiff("src/secret.java","WORKTREE"));Files.createSymbolicLink(root.resolve("src/A.java"),root.resolve("README.md"));rejected("SYMLINK_NOT_ALLOWED",()->service.gitDiff("src/A.java","WORKTREE"));
    }
    @Test void configuredExternalDiffAndFsmonitorCannotExecute()throws Exception{
        initialized();Path marker=root.resolve("FORBIDDEN-MARKER");git("config","core.fsmonitor","touch "+marker);git("config","diff.external","touch "+marker);git("config","pager.diff","touch "+marker);file("src/A.java","class A { int value=2; }\n");
        var service=new WorkspaceService(root);assertThat(service.gitStatus().changes()).isNotEmpty();assertThat(service.gitDiff("src/A.java","WORKTREE").patch()).contains("value=2");assertThat(Files.exists(marker)).isFalse();
    }
    @Test void cleanFilterAndIncludeConfigurationsAreRejectedBeforeCommandsRun()throws Exception{
        initialized();Path marker=root.resolve("FORBIDDEN-MARKER");git("config","filter.danger.clean","touch "+marker);file(".gitattributes","*.java filter=danger\n");
        var service=new WorkspaceService(root);rejected("UNSAFE_GIT_CONFIGURATION",service::gitStatus);rejected("UNSAFE_GIT_CONFIGURATION",()->service.gitDiff("src/A.java","WORKTREE"));assertThat(Files.exists(marker)).isFalse();
        git("config","--remove-section","filter.danger");git("config","include.path","/outside/not-loaded");rejected("UNSAFE_GIT_CONFIGURATION",service::gitStatus);
        git("config","--remove-section","include");git("config","extensions.worktreeConfig","true");
        file(".git/config.worktree","[filter \"danger\"]\n clean = touch "+marker+"\n");
        rejected("UNSAFE_GIT_CONFIGURATION",()->service.gitDiff("src/A.java","WORKTREE"));assertThat(Files.exists(marker)).isFalse();
    }
    @Test void gitRedirectsAndMetadataSymlinksAreRejected()throws Exception{
        file(".git","gitdir: /elsewhere");var service=new WorkspaceService(root);rejected("UNSAFE_GIT_REPOSITORY",service::gitStatus);Files.delete(root.resolve(".git"));initialized();
        file(".git/commondir","/elsewhere");rejected("UNSAFE_GIT_REPOSITORY",service::gitStatus);Files.delete(root.resolve(".git/commondir"));
        file(".git/objects/info/alternates","/elsewhere");rejected("UNSAFE_GIT_REPOSITORY",service::gitStatus);Files.delete(root.resolve(".git/objects/info/alternates"));
        Files.createSymbolicLink(root.resolve(".git/linked"),root.resolve("README.md"));rejected("UNSAFE_GIT_REPOSITORY",service::gitStatus);
    }
    @Test void largeUtf8PatchAndManyStatusesStayBoundedWithTruthfulTruncation()throws Exception{
        initialized();file("src/A.java",("русский😀 "+"x".repeat(80)+"\n").repeat(200));var service=new WorkspaceService(root);var patch=service.gitDiff("src/A.java","WORKTREE");assertThat(patch.truncated()).isTrue();assertThat(WorkspaceJson.fits(patch)).isTrue();assertThat(patch.patch()).doesNotContain("\ufffd");
        for(int i=0;i<80;i++)file("src/LongName%03d.java".formatted(i),"x");var status=service.gitStatus();assertThat(status.changes().size()).isLessThanOrEqualTo(40);assertThat(status.totalChanges()).isGreaterThan(status.changes().size());assertThat(status.omittedChanges()).isPositive();assertThat(WorkspaceJson.fits(status)).isTrue();
    }
    @Test void deeplyNestedMetadataIsRejectedRatherThanLeavingUninspectedSymlinks()throws Exception{
        initialized();String deep=".git/"+"deep/".repeat(12);Files.createDirectories(root.resolve(deep));Files.createSymbolicLink(root.resolve(deep).resolve("link"),root.resolve("README.md"));
        rejected("UNSAFE_GIT_REPOSITORY",new WorkspaceService(root)::gitStatus);
    }
    @Test void binaryCurrentFileIsNeverReturnedAsPatch()throws Exception{
        initialized();Files.write(root.resolve("src/A.java"),new byte[]{1,2,0,3});rejected("BINARY_FILE",()->new WorkspaceService(root).gitDiff("src/A.java","WORKTREE"));
    }
}
