CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS turns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL REFERENCES conversations(id),
    question TEXT NOT NULL,
    answer TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL CHECK (status IN ('pending','completed','failed','cancelled','interrupted')),
    error_code TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS turns_by_conversation ON turns(conversation_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS one_pending_turn ON turns(conversation_id) WHERE status = 'pending';
