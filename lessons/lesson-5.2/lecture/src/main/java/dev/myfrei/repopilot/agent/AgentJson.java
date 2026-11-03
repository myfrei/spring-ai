package dev.myfrei.repopilot.agent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
/** Strict parsing and deterministic hashes, without coercing fractional integers. */
public final class AgentJson {
 private AgentJson(){}
 private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 public static Object parse(String text){return JSON.readValue(text,Object.class);}
 public static String encode(Object value){return JSON.writeValueAsString(value);}
 public static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
 public static String canonical(Object value){return encode(sorted(value));}
 private static Object sorted(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put((String)k,sorted(v)));return sorted;}if(value instanceof List<?> list)return list.stream().map(AgentJson::sorted).toList();return value;}
 @SuppressWarnings("unchecked")public static Map<String,Object> arguments(String raw,Set<String> allowed,Set<String> required){
  Object value=parse(raw);if(!(value instanceof Map<?,?> map)||!allowed.containsAll(map.keySet())||!map.keySet().containsAll(required))throw new IllegalArgumentException("Closed JSON arguments required");return (Map<String,Object>)map;
 }
}
