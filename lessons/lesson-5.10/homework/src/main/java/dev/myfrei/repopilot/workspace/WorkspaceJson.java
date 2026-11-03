package dev.myfrei.repopilot.workspace;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/** Same encoded-data budget as AgentEngine; never truncate JSON after recording a read. */
public final class WorkspaceJson {
  public static final int MAX_RESULT_BYTES = 4096;
  public static final JsonMapper MAPPER = JsonMapper.builder().build();

  private WorkspaceJson() {}

  public static String encode(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static boolean fits(Object value) {
    return encode(java.util.Map.of("ok", true, "code", "OK", "data", value))
            .getBytes(StandardCharsets.UTF_8)
            .length
        <= MAX_RESULT_BYTES;
  }

  public static String bounded(Object value) {
    String json = encode(value);
    if (!fits(value)) throw new WorkspaceException("OUTPUT_LIMIT");
    return json;
  }
}
