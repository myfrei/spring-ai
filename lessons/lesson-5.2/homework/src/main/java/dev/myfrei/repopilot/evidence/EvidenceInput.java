package dev.myfrei.repopilot.evidence;

import java.util.*;

/** Provided metadata boundary; neither file text nor caller-provided read receipts are accepted by HTTP. */
public record EvidenceInput(UUID runId,List<Claim> claims,List<ReadSpan> reads,Map<String,String> currentHashes){
    public EvidenceInput{
        if(runId==null||claims==null||claims.isEmpty()||claims.size()>12||claims.stream().anyMatch(Objects::isNull)||reads==null||reads.size()>256||reads.stream().anyMatch(Objects::isNull)||currentHashes==null||currentHashes.size()>12)throw new IllegalArgumentException("Invalid bounded evidence input");
        currentHashes.forEach((path,hash)->{EvidenceInput.path(path);EvidenceInput.hash(hash);});
        claims=List.copyOf(claims);reads=List.copyOf(reads);currentHashes=Map.copyOf(currentHashes);
    }
    public record Claim(String path,String fullFileHash,int startLine,int endLine){public Claim{EvidenceInput.path(path);EvidenceInput.hash(fullFileHash);EvidenceInput.range(startLine,endLine);}}
    public record ReadSpan(UUID runId,String path,String fullFileHash,int startLine,int endLine){public ReadSpan{if(runId==null)throw new IllegalArgumentException("runId required");EvidenceInput.path(path);EvidenceInput.hash(fullFileHash);EvidenceInput.range(startLine,endLine);}}
    private static void path(String value){if(value==null||value.isBlank()||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>240||value.startsWith("/")||value.endsWith("/")||value.contains("\\")||value.contains(":")||value.chars().anyMatch(Character::isISOControl)||Arrays.stream(value.split("/",-1)).anyMatch(segment->segment.isEmpty()||segment.equals(".")||segment.equals("..")))throw new IllegalArgumentException("Expected normalized relative path");}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected SHA-256");}
    private static void range(int start,int end){if(start<1||end<start||end>100000)throw new IllegalArgumentException("Invalid inclusive line range");}
}
