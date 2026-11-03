package dev.myfrei.repopilot.http;

import java.util.Map;

/** Public metadata extension for annotation methods accepting raw CallToolRequest. */
public interface ToolInputSchemas {
    Map<String,Map<String,Object>> toolInputSchemas();
}
