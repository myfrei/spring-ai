package dev.myfrei.repopilot.ingestion;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VectorValidatorTest {
    private final VectorValidator validator = new VectorValidator();
    @Test void copiesValidVectorsAndPreservesOrder() {
        float[] first = {1, 0, -1};
        var result = validator.validate(List.of(first, new float[] {0, 2, 0}), 2, 3);
        first[0] = 99;
        assertThat(result.getFirst()).containsExactly(1f, 0f, -1f);
        assertThat(result.getLast()).containsExactly(0f, 2f, 0f);
        assertThatThrownBy(() -> result.getFirst().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsWrongCardinalityAndDimensions() {
        assertThatThrownBy(() -> validator.validate(List.of(new float[] {1, 2}), 2, 2)).isInstanceOf(IngestionProblem.class);
        assertThatThrownBy(() -> validator.validate(List.of(new float[] {1, 2}), 1, 3)).isInstanceOf(IngestionProblem.class);
    }
    @Test void rejectsNonfiniteAndZeroVectors() {
        for (float[] bad : List.of(new float[] {Float.NaN, 1}, new float[] {Float.POSITIVE_INFINITY, 1}, new float[] {0, 0})) {
            assertThatThrownBy(() -> validator.validate(List.of(bad), 1, 2)).isInstanceOf(IngestionProblem.class)
                    .extracting("code").isEqualTo("INVALID_VECTOR");
        }
    }
}
