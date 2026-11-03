package dev.myfrei.repopilot.conversation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class SqliteHistory {
    private final String url;

    public SqliteHistory(@Value("${repopilot.history.database}") String database) {
        Path file = Path.of(database).toAbsolutePath();
        this.url = "jdbc:sqlite:" + file;
        try {
            Files.createDirectories(file.getParent());
            try (var input = getClass().getResourceAsStream("/history-schema.sql")) {
                if (input == null) throw new IOException("Schema resource missing");
                String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                withConnection(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("PRAGMA journal_mode=WAL");
                        for (String sql : schema.split(";")) {
                            if (!sql.isBlank()) statement.execute(sql);
                        }
                        // Один процесс владеет файлом: незавершённые запросы предыдущего запуска не продолжаются.
                        statement.executeUpdate("UPDATE turns SET status='interrupted', error_code='RESTART' WHERE status='pending'");
                    }
                    return null;
                });
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot initialize history directory/schema", ex);
        }
    }

    public synchronized Conversation create(String title) {
        if (title == null || title.isBlank() || title.length() > 80) {
            throw new IllegalArgumentException("Title must contain 1..80 characters");
        }
        var conversation = new Conversation(UUID.randomUUID().toString(), title.strip(),
                Instant.now().toString(), Instant.now().toString());
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO conversations(id,title,created_at,updated_at) VALUES (?,?,?,?)")) {
                statement.setString(1, conversation.id()); statement.setString(2, conversation.title());
                statement.setString(3, conversation.createdAt()); statement.setString(4, conversation.updatedAt());
                statement.executeUpdate();
            }
            return conversation;
        });
    }

    public synchronized List<Conversation> list() {
        return withConnection(connection -> {
            var result = new ArrayList<Conversation>();
            try (var statement = connection.prepareStatement("SELECT * FROM conversations ORDER BY updated_at DESC, id" );
                    var rows = statement.executeQuery()) {
                while (rows.next()) result.add(conversation(rows));
            }
            return List.copyOf(result);
        });
    }

    public synchronized Conversation get(String id) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT * FROM conversations WHERE id=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new ConversationProblem("NOT_FOUND", "Диалог не найден.");
                    return conversation(rows);
                }
            }
        });
    }

    public synchronized long begin(String id, String question) {
        get(id);
        if (question == null || question.isBlank() || question.length() > 4000) {
            throw new IllegalArgumentException("Question must contain 1..4000 characters");
        }
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                try (var insert = connection.prepareStatement(
                        "INSERT INTO turns(conversation_id,question,status) VALUES (?,?,'pending')")) {
                    insert.setString(1, id); insert.setString(2, question.strip()); insert.executeUpdate();
                }
                long turn;
                try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT last_insert_rowid()")) {
                    row.next(); turn = row.getLong(1);
                }
                touch(connection, id);
                connection.commit();
                return turn;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback(); throw ex;
            }
        });
    }

    public synchronized void append(long turn, String delta) {
        withConnection(connection -> {
            try (var statement = connection.prepareStatement("UPDATE turns SET answer=answer || ? WHERE id=? AND status='pending'")) {
                statement.setString(1, delta); statement.setLong(2, turn); statement.executeUpdate();
            }
            return null;
        });
    }

    public synchronized void finish(long turn, String status, String code) {
        if (!List.of("completed", "failed", "cancelled", "interrupted").contains(status)) {
            throw new IllegalArgumentException("Not a terminal status");
        }
        withConnection(connection -> {
            try (var statement = connection.prepareStatement("UPDATE turns SET status=?,error_code=? WHERE id=? AND status='pending'")) {
                statement.setString(1, status); statement.setString(2, code); statement.setLong(3, turn);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public synchronized List<Turn> turns(String id) {
        get(id);
        return queryTurns("SELECT * FROM turns WHERE conversation_id=? ORDER BY id", id, 0);
    }

    public synchronized List<Turn> completedWindow(String id, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive window required");
        return queryTurns("SELECT * FROM (SELECT * FROM turns WHERE conversation_id=? AND status='completed' ORDER BY id DESC LIMIT ?) ORDER BY id", id, limit);
    }

    private List<Turn> queryTurns(String sql, String id, int limit) {
        return withConnection(connection -> {
            var result = new ArrayList<Turn>();
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                if (limit > 0) statement.setInt(2, limit);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new Turn(rows.getLong("id"), rows.getString("conversation_id"),
                            rows.getString("question"), rows.getString("answer"), rows.getString("status"), rows.getString("error_code")));
                }
            }
            return List.copyOf(result);
        });
    }

    private static Conversation conversation(ResultSet row) throws SQLException {
        return new Conversation(row.getString("id"), row.getString("title"), row.getString("created_at"), row.getString("updated_at"));
    }

    private static void touch(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE conversations SET updated_at=? WHERE id=?")) {
            statement.setString(1, Instant.now().toString()); statement.setString(2, id); statement.executeUpdate();
        }
    }

    private <T> T withConnection(SqlFunction<T> action) {
        try (var connection = DriverManager.getConnection(this.url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            return action.apply(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("History storage unavailable", ex);
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> { T apply(Connection connection) throws SQLException; }
}
