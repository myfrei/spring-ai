package dev.myfrei.repopilot.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() { }
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static String identity(String... fields) {
        var text = new StringBuilder();
        for (String field : fields) text.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        return sha256(text.toString());
    }
}
