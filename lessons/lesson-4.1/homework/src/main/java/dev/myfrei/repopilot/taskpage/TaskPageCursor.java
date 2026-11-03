package dev.myfrei.repopilot.taskpage;

import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import tools.jackson.databind.json.JsonMapper;

/** A cursor binds a position to data and filter; it is not an authorization token. */
public final class TaskPageCursor {
    private TaskPageCursor() { }
    public static String version(List<TaskPage.Row> sortedRows) {
        return Hashes.sha256(JsonMapper.builder().build().writeValueAsString(sortedRows));
    }
    public static String encode(String version,String status,int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((version+"|"+status+"|"+offset).getBytes(StandardCharsets.UTF_8));
    }
    public static int decode(String cursor,String version,String status,int total) {
        if(cursor==null||cursor.isBlank()) return 0;
        try {
            if(cursor.length()>200)throw invalid();
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
            String[] parts=decoded.split("\\|",-1);
            if(parts.length!=3||!parts[0].equals(version)||!parts[1].equals(status)||!parts[2].matches("[1-9][0-9]{0,2}"))throw invalid();
            int offset=Integer.parseInt(parts[2]);
            if(offset>=total)throw invalid();
            return offset;
        }catch(RuntimeException error){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid or stale task cursor");}
}
