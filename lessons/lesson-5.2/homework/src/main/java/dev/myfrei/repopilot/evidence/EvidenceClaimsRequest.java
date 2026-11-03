package dev.myfrei.repopilot.evidence;
import java.util.*;
import dev.myfrei.repopilot.agent.AgentJson;
/** Closed raw JSON decoder: caller submits claims, never receipts or current hashes. */
public record EvidenceClaimsRequest(List<EvidenceInput.Claim> claims){
 public EvidenceClaimsRequest{if(claims==null||claims.isEmpty()||claims.size()>12||claims.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Expected1–12 claims");claims=List.copyOf(claims);}
 public static EvidenceClaimsRequest decode(String raw){
  try{return parse(raw);}catch(IllegalArgumentException invalid){throw invalid;}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid closed evidence JSON");}
 }
 private static EvidenceClaimsRequest parse(String raw){
  if(raw==null||AgentJson.bytes(raw)>8192)throw new IllegalArgumentException("Evidence request too large");
  var args=AgentJson.arguments(raw,Set.of("claims"),Set.of("claims"));
  if(!(args.get("claims") instanceof List<?> values)||values.isEmpty()||values.size()>12)throw new IllegalArgumentException("Expected claims list");
  var claims=new ArrayList<EvidenceInput.Claim>();
  for(var value:values){
   if(!(value instanceof Map<?,?> map)||!map.keySet().equals(Set.of("path","fullFileHash","startLine","endLine"))||!(map.get("path") instanceof String path)||!(map.get("fullFileHash") instanceof String hash)||!(map.get("startLine") instanceof Integer start)||!(map.get("endLine") instanceof Integer end))throw new IllegalArgumentException("Invalid claim fields");
   claims.add(new EvidenceInput.Claim(path,hash,start,end));
  }
  return new EvidenceClaimsRequest(claims);
 }
}
