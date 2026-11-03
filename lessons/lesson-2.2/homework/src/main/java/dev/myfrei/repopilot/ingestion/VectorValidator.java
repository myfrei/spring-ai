package dev.myfrei.repopilot.ingestion;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VectorValidator {
    public List<List<Float>> validate(List<float[]> vectors, int expectedCount, int dimensions) {
        if (vectors == null || vectors.size() != expectedCount || expectedCount < 1 || dimensions < 1) fail();
        var result = new ArrayList<List<Float>>();
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dimensions) fail();
            var copy = new ArrayList<Float>();
            double norm = 0;
            for (float value : vector) {
                if (!Float.isFinite(value)) fail();
                copy.add(value); norm += (double) value * value;
            }
            if (norm == 0) fail();
            result.add(List.copyOf(copy));
        }
        return List.copyOf(result);
    }
    private static void fail() {
        throw new IngestionProblem("INVALID_VECTOR", "Embedding-модель вернула некорректные векторы. Предыдущий индекс сохранён.");
    }
}
