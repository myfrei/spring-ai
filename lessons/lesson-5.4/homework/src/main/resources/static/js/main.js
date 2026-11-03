import { mountConversations } from "./conversations.js";

mountConversations(document);

import { mountIngestion } from "./ingestion.js";
mountIngestion(document);

import { mountRag } from "./rag.js";
mountRag(document);

import { mountEvaluation } from "./evaluation.js";
mountEvaluation(document);

import { mountDiagnostics } from "./diagnostics.js";
mountDiagnostics(document);
