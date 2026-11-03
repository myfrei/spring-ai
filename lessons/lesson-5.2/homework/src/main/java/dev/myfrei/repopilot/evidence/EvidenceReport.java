package dev.myfrei.repopilot.evidence;

import java.util.*;
public record EvidenceReport(UUID runId,List<Check> checks,boolean allSupported){
    public EvidenceReport{checks=List.copyOf(checks);}
    public enum Status{SUPPORTED,NOT_READ,UNAVAILABLE,STALE,OUTSIDE_READ_RANGES}
    public record Check(String path,String fullFileHash,int startLine,int endLine,Status status){}
}
