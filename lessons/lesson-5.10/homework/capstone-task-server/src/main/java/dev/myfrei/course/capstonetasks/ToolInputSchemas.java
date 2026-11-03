package dev.myfrei.course.capstonetasks;

import java.util.Map;

/** Public metadata extension for annotation methods accepting raw CallToolRequest. */
public interface ToolInputSchemas {
  Map<String, Map<String, Object>> toolInputSchemas();
}
