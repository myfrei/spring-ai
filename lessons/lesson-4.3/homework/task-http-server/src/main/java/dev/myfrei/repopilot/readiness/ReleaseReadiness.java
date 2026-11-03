package dev.myfrei.repopilot.readiness;
import java.util.List;
public record ReleaseReadiness(String taskId,int revision,Verdict verdict,List<Check> checks,int ignoredStaleResults,List<String> reasons,String origin) {
    public enum Verdict { READY,BLOCKED,UNKNOWN }
    public enum CheckState { PASS,FAIL,UNKNOWN,MISSING,CONFLICT }
    public record Check(String name,CheckState state,int attempt) { }
    public ReleaseReadiness {checks=List.copyOf(checks);reasons=List.copyOf(reasons);}
}
