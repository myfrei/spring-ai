package dev.myfrei.repopilot.rag;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

public class SelectedDocumentsStore implements VectorStore {
    private final String question;
    private final List<Document> documents;
    public SelectedDocumentsStore(String question, List<Document> documents) {
        this.question = question; this.documents = List.copyOf(documents);
    }
    @Override public List<Document> similaritySearch(SearchRequest request) {
        if (!this.question.equals(request.getQuery())) throw new IllegalArgumentException("Unexpected advisor query");
        return this.documents;
    }
    @Override public void add(List<Document> documents) { throw new UnsupportedOperationException("Read-only selection"); }
    @Override public void delete(List<String> ids) { throw new UnsupportedOperationException("Read-only selection"); }
    @Override public void delete(Filter.Expression expression) { throw new UnsupportedOperationException("Read-only selection"); }
}
