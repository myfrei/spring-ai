package dev.myfrei.repopilot.query;
import java.util.*;
import org.springframework.ai.chat.messages.*;
public record QueryRequest(String question,Mode mode,List<Turn> history,Integer topK,Double threshold){
 public enum Mode{DIRECT,CONTEXTUAL,EXPANDED}
 public record Turn(String question,String answer){public Turn{if(question==null||answer==null||question.isBlank()||answer.isBlank()||question.length()>1500||answer.length()>1500)throw new IllegalArgumentException("Invalid history turn");}}
 public QueryRequest{
  if(question==null||question.isBlank()||question.length()>4000)throw new IllegalArgumentException("Question 1..4000");
  if(mode==null)mode=Mode.DIRECT;history=history==null?List.of():List.copyOf(history);
  if(history.size()>2)throw new IllegalArgumentException("At most two complete history turns");
  if(topK==null)topK=4;if(threshold==null)threshold=.35;
  if(topK<1||topK>8||!Double.isFinite(threshold)||threshold<0||threshold>1)throw new IllegalArgumentException("Invalid retrieval options");
 }
 public List<Message> messages(){var messages=new ArrayList<Message>();for(var turn:history){messages.add(new UserMessage(turn.question()));messages.add(new AssistantMessage(turn.answer()));}return List.copyOf(messages);}
}
