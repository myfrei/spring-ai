package dev.myfrei.repopilot.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class RagDatabase {
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager transactions;
    private final int dimensions;
    private boolean initialized;

    public RagDatabase(String url, String username, String password, int dimensions) {
        if (dimensions < 1 || dimensions > 2000) throw new IllegalArgumentException("Use dimensions 1..2000");
        var source = new DriverManagerDataSource(url, username, password);
        var properties = new java.util.Properties();
        properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "15");
        source.setConnectionProperties(properties);
        this.jdbc = new JdbcTemplate(source);
        this.transactions = new DataSourceTransactionManager(source);
        this.dimensions = dimensions;
    }

    public synchronized void ensureSchema() {
        if (this.initialized) return;
        try {
            this.jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            this.jdbc.execute("CREATE TABLE IF NOT EXISTS rag_vectors (id text PRIMARY KEY, content text NOT NULL, metadata json NOT NULL, embedding vector(" + this.dimensions + ") NOT NULL)");
            this.jdbc.execute("CREATE TABLE IF NOT EXISTS rag_active_indexes (workspace_id text PRIMARY KEY, manifest jsonb NOT NULL)");
            String type = this.jdbc.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='rag_vectors'::regclass AND attname='embedding'", String.class);
            if (!("vector(" + this.dimensions + ")").equals(type)) {
                throw new RagProblem("INDEX_DIMENSIONS", "Размерность таблицы pgvector отличается от encoder. Нужна отдельная БД или явная миграция.");
            }
            this.initialized = true;
        } catch (RagProblem error) { throw error; }
        catch (org.springframework.dao.DataAccessException error) { throw unavailable(); }
    }

    public JdbcTemplate jdbc() { return this.jdbc; }
    public TransactionTemplate writeTransaction() { return new TransactionTemplate(this.transactions); }
    public TransactionTemplate readTransaction() {
        var transaction = new TransactionTemplate(this.transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); transaction.setReadOnly(true);
        return transaction;
    }
    public PgVectorStore store(EmbeddingModel model) {
        return PgVectorStore.builder(this.jdbc, model).dimensions(this.dimensions)
                .vectorTableName("rag_vectors").idType(PgVectorStore.PgIdType.TEXT)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE).indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(false).vectorTableValidationsEnabled(false).build();
    }
    public static RagProblem unavailable() {
        return new RagProblem("RAG_DATABASE_UNAVAILABLE", "PostgreSQL/pgvector недоступен. Запустите docker compose up -d и проверьте настройки подключения.");
    }
}
