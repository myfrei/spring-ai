package dev.myfrei.repopilot.workspace;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Local teaching boundary, not an OS sandbox or an atomic filesystem snapshot. */
public final class WorkspaceService {
    public static final int MAX_FILE_BYTES=65_536,MAX_SCAN_ENTRIES=512,MAX_DEPTH=12;
    public static final int MAX_SEARCH_BYTES=2_097_152,MAX_ITEMS=40,MAX_READ_LINES=80;
    public static final int MAX_PROCESS_BYTES=65_536,GIT_TIMEOUT_SECONDS=3;
    private static final Set<String> TEXT_EXTENSIONS=Set.of("java","xml","md","txt","properties","yaml","yml","json","sql");
    private final Path root;
    private final WorkspaceReadLedger ledger;
    public WorkspaceService(Path root){this(root,new WorkspaceReadLedger());}
    public WorkspaceService(Path root,WorkspaceReadLedger ledger){
        if(root==null||ledger==null)throw new WorkspaceException("INVALID_WORKSPACE_ROOT");
        try {
            Path absolute=root.toAbsolutePath().normalize();
            if(Files.isSymbolicLink(absolute)||!Files.isDirectory(absolute,NOFOLLOW_LINKS))throw new WorkspaceException("INVALID_WORKSPACE_ROOT");
            this.root=absolute.toRealPath();this.ledger=ledger;
        }catch(IOException error){throw new WorkspaceException("INVALID_WORKSPACE_ROOT");}
    }
    public record FileEntry(String path,long bytes) { }
    public record FileListing(String directory,List<FileEntry> files,int totalFiles,boolean scanComplete,boolean truncated){public FileListing{files=List.copyOf(files);}}
    public record FileRead(String path,String fullFileHash,long totalBytes,int totalLines,int startLine,int endLine,String content,boolean truncated,boolean rangeComplete) { }
    public record Match(String path,int line,String text,boolean lineTruncated,String fullFileHash) { }
    public record SearchResult(String directory,String query,List<Match> matches,int totalMatches,long searchedBytes,long readBudgetBytes,boolean scanComplete,boolean truncated){public SearchResult{matches=List.copyOf(matches);}}
    public record GitChange(String index,String worktree,String path,String originalPath) { }
    public record GitStatus(List<GitChange> changes,int totalChanges,int omittedChanges,boolean truncated){public GitStatus{changes=List.copyOf(changes);}}
    public record GitDiff(String path,String scope,String patch,boolean truncated) { }
    private record Snapshot(byte[] bytes,String text,String hash,List<String> lines) { }
    private record Scan(List<Path> files,boolean complete) { }

    public FileListing listFiles(String directory,int maxEntries) {
        count(maxEntries);String dir=directory(directory);Scan scan=scan(dir);var files=new ArrayList<FileEntry>();int total=scan.files().size();
        for(Path file:scan.files()) {
            if(files.size()>=maxEntries)break;
            try {
                files.add(new FileEntry(relative(file),Files.size(file)));
                var candidate=new FileListing(dir,files,total,scan.complete(),files.size()<total||!scan.complete());
                if(!WorkspaceJson.fits(candidate)){files.removeLast();break;}
            }catch(IOException error){throw new WorkspaceException("READ_FAILED");}
        }
        return new FileListing(dir,files,total,scan.complete(),files.size()<total||!scan.complete());
    }
    public FileRead readFile(String runId,String path,int startLine,int endLine) {
        WorkspaceReadLedger.validateRun(runId);
        if(startLine<1||endLine<startLine||(long)endLine-startLine+1>MAX_READ_LINES)throw new WorkspaceException("INVALID_LINE_RANGE");
        Path file=resolveFile(path,false);Snapshot snapshot=read(file);
        if(startLine>snapshot.lines().size())throw new WorkspaceException("INVALID_LINE_RANGE");
        int wanted=Math.min(endLine,snapshot.lines().size()),last=startLine-1;String content="";FileRead result=null;
        for(int line=startLine;line<=wanted;line++) {
            String next=content+(line==startLine?"":"\n")+snapshot.lines().get(line-1);
            var candidate=new FileRead(path,snapshot.hash(),snapshot.bytes().length,snapshot.lines().size(),startLine,line,next,
                    startLine>1||line<snapshot.lines().size(),line==wanted);
            if(!WorkspaceJson.fits(candidate))break;
            content=next;last=line;result=candidate;
        }
        if(last<startLine)throw new WorkspaceException("OUTPUT_LIMIT");
        // A receipt is committed only after successful bounded encoding of complete returned lines.
        WorkspaceJson.bounded(result);
        this.ledger.record(new ReadReceipt(runId,path,snapshot.hash(),startLine,last));
        return result;
    }
    public List<ReadReceipt> receipts(String runId){return this.ledger.receipts(runId);}
    public String currentHash(String path){return read(resolveFile(path,false)).hash();}

    public SearchResult searchText(String query,String directory,int maxResults) {
        count(maxResults);if(query==null||query.isBlank()||utf8(query)>160||query.codePoints().anyMatch(Character::isISOControl))throw new WorkspaceException("INVALID_QUERY");
        String dir=directory(directory);Scan scan=scan(dir);var matches=new ArrayList<Match>();long work=0,charged=0;int total=0;boolean complete=scan.complete(),outputFull=false;
        for(Path file:scan.files()) {
            long size;
            try{size=Files.size(file);}catch(IOException error){throw new WorkspaceException("READ_FAILED");}
            if(size>MAX_FILE_BYTES)continue;
            if(charged+MAX_FILE_BYTES+1L>MAX_SEARCH_BYTES){complete=false;break;}
            charged+=MAX_FILE_BYTES+1L; // Reserve before reading, even when UTF-8 decoding later fails.
            Snapshot snapshot;
            try{snapshot=read(file);}catch(WorkspaceException error){if(Set.of("BINARY_FILE","FILE_TOO_LARGE").contains(error.code()))continue;throw error;}
            work+=snapshot.bytes().length;
            for(int i=0;i<snapshot.lines().size();i++)if(snapshot.lines().get(i).contains(query)) {
                total++;String line=snapshot.lines().get(i);String excerpt=prefix(line,160);
                if(matches.size()<maxResults&&!outputFull) {
                    matches.add(new Match(relative(file),i+1,excerpt,!excerpt.equals(line),snapshot.hash()));
                    if(!WorkspaceJson.fits(new SearchResult(dir,query,matches,total,work,charged,complete,true))){matches.removeLast();outputFull=true;}
                }
            }
        }
        // Metadata may gain digits during the scan; keep the final encoded value bounded too.
        while(!WorkspaceJson.fits(new SearchResult(dir,query,matches,total,work,charged,complete,true))&&!matches.isEmpty())matches.removeLast();
        return new SearchResult(dir,query,matches,total,work,charged,complete,matches.size()<total||!complete);
    }

    public GitStatus gitStatus() {
        verifyGit();var output=git(List.of("status","--porcelain=v1","-z","--untracked-files=all","--renames","--ignore-submodules=all","--","README.md","pom.xml","src/"));
        if(output.exitCode()!=0)throw new WorkspaceException("GIT_FAILED");
        byte[] raw=output.bytes();int completeEnd=raw.length;
        if(output.truncated()){while(completeEnd>0&&raw[completeEnd-1]!=0)completeEnd--;}
        String decoded=decode(Arrays.copyOf(raw,completeEnd));String[] records=decoded.split("\u0000",-1);var changes=new ArrayList<GitChange>();int total=0,omitted=0;
        for(int i=0;i<records.length-1;i++) {
            String record=records[i];if(record.length()<4||record.charAt(2)!=' ')throw new WorkspaceException("GIT_INVALID_OUTPUT");
            String status=record.substring(0,2),path=record.substring(3),original=null;
            if(status.indexOf('R')>=0||status.indexOf('C')>=0){if(i+1>=records.length-1)break;original=records[++i];}
            total++;
            if(!safeMetadataPath(path)||(original!=null&&!safeMetadataPath(original))){omitted++;continue;}
            changes.add(new GitChange(status.substring(0,1),status.substring(1,2),path,original));
            if(changes.size()>MAX_ITEMS||!WorkspaceJson.fits(new GitStatus(changes,total,omitted,true))){changes.removeLast();omitted++;}
        }
        while(!WorkspaceJson.fits(new GitStatus(changes,total,omitted,true))&&!changes.isEmpty()){changes.removeLast();omitted++;}
        return new GitStatus(changes,total,omitted,output.truncated()||omitted>0);
    }
    public GitDiff gitDiff(String path,String scope) {
        if(!Set.of("WORKTREE","STAGED").contains(scope==null?"":scope))throw new WorkspaceException("INVALID_DIFF_SCOPE");
        Path file=resolveFile(path,true);if(Files.exists(file,NOFOLLOW_LINKS))read(file); // Reject binary/oversized current content before Git sees it.
        verifyGit();requireTrackedTextPath(path);var args=new ArrayList<String>(List.of("diff","--no-ext-diff","--no-textconv","--no-renames","--no-color","--unified=3","--src-prefix=a/","--dst-prefix=b/"));
        if(scope.equals("STAGED"))args.add("--cached");args.add("--");args.add(path);
        var output=git(args);if(output.exitCode()!=0)throw new WorkspaceException("GIT_FAILED");
        String patch=output.truncated()?decodePrefix(output.bytes()):decode(output.bytes());if(patch.contains("Binary files ")||patch.contains("GIT binary patch"))throw new WorkspaceException("BINARY_FILE");
        boolean truncated=output.truncated();
        while(!WorkspaceJson.fits(new GitDiff(path,scope,patch,truncated))) {patch=prefix(patch,Math.max(0,utf8(patch)-128));truncated=true;}
        return new GitDiff(path,scope,patch,truncated);
    }

    private String directory(String path) {
        String dir=(path==null||path.equals("."))?".":path;
        if(dir.equals(".")){checkRoot();return dir;}
        Path resolved=resolve(dir,false);if(!Files.isDirectory(resolved,NOFOLLOW_LINKS))throw new WorkspaceException("NOT_A_DIRECTORY");return dir;
    }
    private Scan scan(String directory) {
        Path start=directory.equals(".")?this.root:resolve(directory,false);var files=new ArrayList<Path>();boolean[] complete={true};int[] visited={0};
        try{Files.walkFileTree(start,EnumSet.noneOf(FileVisitOption.class),MAX_DEPTH,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs){
                if(++visited[0]>MAX_SCAN_ENTRIES){complete[0]=false;return FileVisitResult.TERMINATE;}
                if(!dir.equals(root)&&!allowed(relative(dir),true))return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs){
                if(++visited[0]>MAX_SCAN_ENTRIES){complete[0]=false;return FileVisitResult.TERMINATE;}
                if(attrs.isDirectory()){complete[0]=false;return FileVisitResult.CONTINUE;}
                if(attrs.isRegularFile()&&!attrs.isSymbolicLink()&&allowed(relative(file),false))files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException error){complete[0]=false;return FileVisitResult.CONTINUE;}
        });}catch(IOException error){throw new WorkspaceException("READ_FAILED");}
        files.sort(Comparator.comparing(this::relative));return new Scan(List.copyOf(files),complete[0]);
    }
    private Path resolveFile(String path,boolean missingAllowed){if(!allowed(path,false))throw new WorkspaceException("PATH_NOT_ALLOWED");return resolve(path,missingAllowed);}
    private Path resolve(String path,boolean missingAllowed) {
        checkRoot();if(!allowed(path,false)&&!allowed(path,true))throw new WorkspaceException("PATH_NOT_ALLOWED");
        Path resolved=this.root.resolve(path).normalize();if(!resolved.startsWith(this.root))throw new WorkspaceException("PATH_NOT_ALLOWED");
        Path cursor=this.root;
        for(Path part:this.root.relativize(resolved)) {
            cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new WorkspaceException("SYMLINK_NOT_ALLOWED");
            if(!Files.exists(cursor,NOFOLLOW_LINKS)){if(missingAllowed)return resolved;throw new WorkspaceException("NOT_FOUND");}
        }
        try {if(!resolved.toRealPath().startsWith(this.root))throw new WorkspaceException("PATH_NOT_ALLOWED");}
        catch(IOException error){throw new WorkspaceException("READ_FAILED");}
        return resolved;
    }
    private void checkRoot(){if(Files.isSymbolicLink(this.root)||!Files.isDirectory(this.root,NOFOLLOW_LINKS))throw new WorkspaceException("WORKSPACE_UNAVAILABLE");}
    private boolean safeMetadataPath(String path){try{resolve(path,true);return allowed(path,false);}catch(WorkspaceException error){return false;}}
    private static boolean allowed(String path,boolean directory) {
        if(path==null||path.isBlank()||utf8(path)>240||path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf(':')>=0||path.chars().anyMatch(c->"*?[]".indexOf(c)>=0)||path.codePoints().anyMatch(Character::isISOControl))return false;
        String[] parts=path.split("/",-1);
        for(String part:parts){String low=part.toLowerCase(Locale.ROOT);if(part.isEmpty()||part.startsWith(".")||low.contains("secret")||low.contains("credential")||low.contains("password")||low.contains("token")||low.contains("privatekey"))return false;}
        if(path.equals("README.md")||path.equals("pom.xml"))return !directory;
        if(path.equals("src"))return directory;
        if(!path.startsWith("src/"))return false;
        if(directory)return true;
        String name=parts[parts.length-1];int dot=name.lastIndexOf('.');return dot>0&&TEXT_EXTENSIONS.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }
    private Snapshot read(Path file) {
        try {
            if(!Files.isRegularFile(file,NOFOLLOW_LINKS))throw new WorkspaceException("NOT_A_FILE");
            if(Files.size(file)>MAX_FILE_BYTES)throw new WorkspaceException("FILE_TOO_LARGE");
            byte[] bytes;
            try(var channel=Files.newByteChannel(file,Set.of(StandardOpenOption.READ,NOFOLLOW_LINKS));var input=java.nio.channels.Channels.newInputStream(channel)){bytes=input.readNBytes(MAX_FILE_BYTES+1);}
            if(bytes.length>MAX_FILE_BYTES)throw new WorkspaceException("FILE_TOO_LARGE");
            String text=decode(bytes);
            if(text.codePoints().anyMatch(cp->Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t'))throw new WorkspaceException("BINARY_FILE");
            return new Snapshot(bytes,text,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),text.lines().toList());
        }catch(IOException error){throw new WorkspaceException("READ_FAILED");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private void verifyGit() {
        checkRoot();Path git=this.root.resolve(".git");
        if(!Files.exists(git,NOFOLLOW_LINKS))throw new WorkspaceException("NO_GIT_REPOSITORY");
        if(Files.isSymbolicLink(git)||!Files.isDirectory(git,NOFOLLOW_LINKS))throw new WorkspaceException("UNSAFE_GIT_REPOSITORY");
        // Worktree-specific overlays can contain filter/include helpers even in a normal .git directory.
        if(Files.exists(git.resolve("config.worktree"),NOFOLLOW_LINKS))throw new WorkspaceException("UNSAFE_GIT_CONFIGURATION");
        if(Files.exists(git.resolve("commondir"),NOFOLLOW_LINKS)||Files.exists(git.resolve("objects/info/alternates"),NOFOLLOW_LINKS))throw new WorkspaceException("UNSAFE_GIT_REPOSITORY");
        try(var paths=Files.walk(git,MAX_DEPTH)) {
            var iterator=paths.iterator();int seen=0;
            while(iterator.hasNext()){Path path=iterator.next();if(++seen>4096||Files.isSymbolicLink(path)||(git.relativize(path).getNameCount()>=MAX_DEPTH&&Files.isDirectory(path,NOFOLLOW_LINKS)))throw new WorkspaceException("UNSAFE_GIT_REPOSITORY");}
            Path config=git.resolve("config");if(Files.exists(config,NOFOLLOW_LINKS)){
                if(Files.size(config)>16_384)throw new WorkspaceException("UNSAFE_GIT_CONFIGURATION");
                String text=decode(Files.readAllBytes(config));
                // Git status/diff can run configured clean filters. No local includes/filters in this teaching fixture.
                if(text.matches("(?is).*\\[\\s*(include(?:if)?|filter)(?:\\s|\\.|\\]).*"))throw new WorkspaceException("UNSAFE_GIT_CONFIGURATION");
            }
        }catch(IOException error){throw new WorkspaceException("GIT_FAILED");}
    }
    private void requireTrackedTextPath(String path) {
        var indexed=git(List.of("ls-files","--stage","-z","--",path));
        if(indexed.exitCode()!=0)throw new WorkspaceException("GIT_FAILED");
        String metadata=decode(indexed.bytes());
        if(metadata.isEmpty()) {
            var committed=git(List.of("ls-tree","-z","HEAD","--",path));
            if(committed.exitCode()!=0||committed.bytes().length==0)throw new WorkspaceException("NOT_TRACKED");
            metadata=decode(committed.bytes());
        }
        for(String record:metadata.split("\u0000"))if(!record.startsWith("100644 ")&&!record.startsWith("100755 "))throw new WorkspaceException("NOT_A_TEXT_FILE");
    }
    private WorkspaceProcessRunner.Output git(List<String> args) {
        var command=new ArrayList<String>(List.of("git","--no-pager","--no-optional-locks","--literal-pathspecs","--git-dir="+this.root.resolve(".git"),"--work-tree="+this.root,
                "-c","core.fsmonitor=false","-c","core.untrackedCache=false","-c","core.hooksPath=/dev/null","-c","core.attributesFile=/dev/null","-c","core.quotepath=false","-c","color.ui=false","-c","protocol.allow=never"));command.addAll(args);
        return new WorkspaceProcessRunner().run(command,this.root);
    }
    private static String decode(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException error){throw new WorkspaceException("BINARY_FILE");}}
    private static String decodePrefix(byte[] bytes){for(int end=bytes.length;end>=Math.max(0,bytes.length-3);end--){try{return decode(Arrays.copyOf(bytes,end));}catch(WorkspaceException ignored){}}throw new WorkspaceException("BINARY_FILE");}
    private static String prefix(String value,int budget){int end=0,used=0;while(end<value.length()){int cp=value.codePointAt(end),size=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;if(used+size>budget)break;used+=size;end+=Character.charCount(cp);}return value.substring(0,end);}
    private String relative(Path path){return this.root.relativize(path).toString().replace(File.separatorChar,'/');}
    private static int utf8(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static void count(int value){if(value<1||value>MAX_ITEMS)throw new WorkspaceException("INVALID_LIMIT");}
}
