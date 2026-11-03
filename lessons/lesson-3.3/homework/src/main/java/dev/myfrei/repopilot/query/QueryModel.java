package dev.myfrei.repopilot.query;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
public interface QueryModel{
 String digest();
 default void validateContextualize(String question,List<Message> history) { }
 default void validateExpand(String question) { }
 String contextualize(String question,List<Message> history);
 List<String> expand(String question);
}
