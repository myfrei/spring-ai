package dev.myfrei.repopilot.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkspaceServiceTest {
    @TempDir Path root;
    final String run=UUID.randomUUID().toString();
    private WorkspaceService service(){return new WorkspaceService(root);}
    private Path file(String name,String text)throws Exception{Path path=root.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,text);return path;}
    private void rejected(String code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo(code));}
    @Test void readsActualLinesWithFullByteHashAndImmutableSameRunReceipt()throws Exception {
        String original="первая\r\nвторая😀\r\nтретья\n";file("src/Example.java",original);var service=service();
        var result=service.readFile(run,"src/Example.java",2,80);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.content()).isEqualTo("вторая😀\nтретья");assertThat(result.fullFileHash()).isEqualTo(hash);assertThat(result.totalBytes()).isEqualTo(original.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.startLine()).isEqualTo(2);assertThat(result.endLine()).isEqualTo(3);assertThat(result.totalLines()).isEqualTo(3);assertThat(result.rangeComplete()).isTrue();assertThat(result.truncated()).isTrue();
        assertThat(service.receipts(run)).containsExactly(new ReadReceipt(run,"src/Example.java",hash,2,3));assertThatThrownBy(()->service.receipts(run).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void outputBudgetKeepsWholeLinesAndReceiptNeverIncludesOmittedLine()throws Exception {
        file("src/Large.java","😀".repeat(600)+"\n"+"😀".repeat(600));var service=service();var result=service.readFile(run,"src/Large.java",1,2);
        assertThat(result.endLine()).isEqualTo(1);assertThat(result.rangeComplete()).isFalse();assertThat(result.truncated()).isTrue();assertThat(result.content()).isEqualTo("😀".repeat(600));
        assertThat(service.receipts(run).getFirst().endLine()).isEqualTo(1);assertThat(WorkspaceJson.fits(result)).isTrue();
    }
    @Test void oneOversizedLineAndEmptyFileFailWithoutRecordingReceipt()throws Exception {
        file("src/Huge.java","x".repeat(5000));file("src/Empty.java","");var service=service();
        rejected("OUTPUT_LIMIT",()->service.readFile(run,"src/Huge.java",1,1));rejected("INVALID_LINE_RANGE",()->service.readFile(run,"src/Empty.java",1,1));rejected("RECEIPTS_UNAVAILABLE",()->service.receipts(run));
    }
    @Test void traversalAbsoluteHiddenSecretsCommandsPathspecsAndNonTextExtensionsAreDenied()throws Exception {
        var service=service();for(String path:List.of("../README.md","src/../README.md","/etc/passwd","C:/a.java","src\\A.java","src/a\n.java",".git/config","AGENTS.md","config/application.yml","src/.env","src/credentials.yml","src/AccessToken.java","src/A.class","src/*.java","src/?.java","src/[A].java","src//A.java","."))
            rejected("PATH_NOT_ALLOWED",()->service.readFile(run,path,1,1));
        rejected("NOT_FOUND",()->service.readFile(run,"src/Missing.java",1,1));
    }
    @Test void symbolicFilesDirectoriesAndRootLeafAreRejected()throws Exception {
        Path outside=Files.createTempDirectory(root.getParent(),"workspace-outside-");Files.writeString(outside.resolve("A.java"),"outside");Files.createDirectories(root.resolve("src"));
        Files.createSymbolicLink(root.resolve("src/Link.java"),outside.resolve("A.java"));Files.createSymbolicLink(root.resolve("src/linked"),outside);
        var service=service();rejected("SYMLINK_NOT_ALLOWED",()->service.readFile(run,"src/Link.java",1,1));rejected("SYMLINK_NOT_ALLOWED",()->service.readFile(run,"src/linked/A.java",1,1));assertThat(service.listFiles(".",40).files()).isEmpty();
        Path link=root.resolveSibling(root.getFileName()+"-link");Files.createSymbolicLink(link,root);try{rejected("INVALID_WORKSPACE_ROOT",()->new WorkspaceService(link));}finally{Files.delete(link);}
    }
    @Test void invalidUtf8BinaryControlsAndFileSizeBoundAreEnforced()throws Exception {
        Path source=file("src/Bad.java","before");var service=service();
        Files.write(source,new byte[]{(byte)0xc3,0x28});rejected("BINARY_FILE",()->service.readFile(run,"src/Bad.java",1,1));
        Files.writeString(source,"hello\0secret");rejected("BINARY_FILE",()->service.currentHash("src/Bad.java"));
        Files.write(source,new byte[WorkspaceService.MAX_FILE_BYTES+1]);rejected("FILE_TOO_LARGE",()->service.readFile(run,"src/Bad.java",1,1));
    }
    @Test void lineBoundsAndRunIdentityAreValidatedBeforeReceipt()throws Exception {
        file("README.md","a\nb\n");var service=service();
        for(int[] range:List.of(new int[]{0,1},new int[]{2,1},new int[]{1,81},new int[]{3,4},new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE}))rejected("INVALID_LINE_RANGE",()->service.readFile(run,"README.md",range[0],range[1]));
        rejected("INVALID_RUN_ID",()->service.readFile("model-chosen-run","README.md",1,1));
    }
    @Test void listIsSortedFilteredImmutableAndTruthfulWhenCapped()throws Exception {
        file("README.md","docs");file("pom.xml","pom");file("src/Z.java","z");file("src/A.java","a");file("src/secret.txt","private");file("target/Leak.java","private");file("AGENTS.md","execute forbidden command");
        var result=service().listFiles(".",2);assertThat(result.files()).extracting(WorkspaceService.FileEntry::path).containsExactly("README.md","pom.xml");assertThat(result.totalFiles()).isEqualTo(4);assertThat(result.scanComplete()).isTrue();assertThat(result.truncated()).isTrue();assertThatThrownBy(()->result.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(service().listFiles("src",40).files()).extracting(WorkspaceService.FileEntry::path).containsExactly("src/A.java","src/Z.java");
    }
    @Test void searchIsLiteralUsesOneMatchPerLineAndDoesNotCreateReadReceipts()throws Exception {
        file("src/A.java","a.*b and a.*b\na123b\nA.*b\n");file("src/credentials.txt","a.*b PRIVATE");var service=service();
        var result=service.searchText("a.*b",".",10);assertThat(result.totalMatches()).isEqualTo(1);assertThat(result.matches()).hasSize(1);assertThat(result.matches().getFirst().line()).isEqualTo(1);assertThat(result.matches().getFirst().text()).isEqualTo("a.*b and a.*b");assertThat(result.scanComplete()).isTrue();
        rejected("RECEIPTS_UNAVAILABLE",()->service.receipts(run));
    }
    @Test void searchReportsOutputTruncationAndDoesNotReadPastWorkBudget()throws Exception {
        for(int i=0;i<40;i++)file("src/F%02d.txt".formatted(i),"match "+"x".repeat(65_500));
        var result=service().searchText("match","src",1);assertThat(result.matches()).hasSize(1);assertThat(result.matches().getFirst().lineTruncated()).isTrue();assertThat(result.truncated()).isTrue();assertThat(result.scanComplete()).isFalse();assertThat(result.searchedBytes()).isLessThanOrEqualTo(WorkspaceService.MAX_SEARCH_BYTES);assertThat(WorkspaceJson.fits(result)).isTrue();
        // Rejected binary bytes still consume the work budget before decode, not after it.
        for(int i=0;i<40;i++)Files.write(root.resolve("src/F%02d.txt".formatted(i)),new byte[65_500]);
        var binary=service().searchText("match","src",1);assertThat(binary.matches()).isEmpty();assertThat(binary.searchedBytes()).isZero();assertThat(binary.readBudgetBytes()).isPositive().isLessThanOrEqualTo(WorkspaceService.MAX_SEARCH_BYTES);assertThat(binary.scanComplete()).isFalse();assertThat(binary.truncated()).isTrue();
    }
    @Test void traversalScanAndDepthHaveExplicitCompletionSignals()throws Exception {
        for(int i=0;i<540;i++)file("src/F%03d.java".formatted(i),"x");var listing=service().listFiles("src",40);assertThat(listing.scanComplete()).isFalse();assertThat(listing.truncated()).isTrue();assertThat(listing.totalFiles()).isLessThanOrEqualTo(WorkspaceService.MAX_SCAN_ENTRIES);
    }
    @Test void currentHashRereadsWithoutCreatingOrReplacingReceipts()throws Exception {
        file("src/A.java","old\n");var service=service();var before=service.readFile(run,"src/A.java",1,1);file("src/A.java","new\n");assertThat(service.currentHash("src/A.java")).isNotEqualTo(before.fullFileHash());assertThat(service.receipts(run)).containsExactly(new ReadReceipt(run,"src/A.java",before.fullFileHash(),1,1));
    }
    @Test void invalidDirectoryQueryAndLimitAreTyped()throws Exception {
        file("README.md","readme");var service=service();rejected("NOT_A_DIRECTORY",()->service.listFiles("README.md",1));
        rejected("INVALID_LIMIT",()->service.listFiles(".",0));rejected("INVALID_LIMIT",()->service.searchText("x",".",41));
        for(String query:List.of(""," ","x\ny","a".repeat(161)))rejected("INVALID_QUERY",()->service.searchText(query,".",1));
    }
    @Test void quotesTabsAndUnicodeUseActualEnvelopeBudgetBeforeReceipt()throws Exception {
        file("src/A.java",("\"\t😀".repeat(300)+"\n").repeat(3));var service=service();var result=service.readFile(run,"src/A.java",1,3);
        var data=dev.myfrei.repopilot.agent.AgentJson.parse(WorkspaceJson.bounded(result));String envelope=dev.myfrei.repopilot.agent.AgentJson.canonical(Map.of("ok",true,"code","OK","data",data));
        assertThat(envelope.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);assertThat(result.endLine()).isEqualTo(1);assertThat(service.receipts(run).getFirst().endLine()).isEqualTo(1);
    }
}
