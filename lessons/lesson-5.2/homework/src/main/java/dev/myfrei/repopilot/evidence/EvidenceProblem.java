package dev.myfrei.repopilot.evidence;
public final class EvidenceProblem extends RuntimeException{
 private final String code;
 public EvidenceProblem(String code){super(code);this.code=code;}
 public String code(){return code;}
}
