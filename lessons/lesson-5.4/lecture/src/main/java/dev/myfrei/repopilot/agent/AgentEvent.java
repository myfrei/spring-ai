package dev.myfrei.repopilot.agent;
import java.util.*;
public record AgentEvent(long seq,String type,Map<String,Object> attributes){public AgentEvent{attributes=Map.copyOf(attributes);}}
