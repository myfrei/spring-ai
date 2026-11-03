package dev.myfrei.repopilot.rerank;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import dev.myfrei.repopilot.rag.RagProblem;
import static org.assertj.core.api.Assertions.*;

class StrictRerankScoresTest {
    static final String A="a".repeat(64),B="b".repeat(64);
    static String row(String id,String score) { return "{\"chunkId\":\""+id+"\",\"score\":"+score+"}"; }
    static String root(String rows) { return "{\"scores\":["+rows+"]}"; }
    @Test void acceptsEveryKnownIdExactlyOnceAndReturnsImmutableNumericScores() {
        var result=StrictRerankScores.parse(root(row(B,"1")+","+row(A,"-0.0")),Set.of(A,B));
        assertThat(result).containsEntry(A,0.0).containsEntry(B,1.0);
        assertThatThrownBy(()->result.put(A,.3)).isInstanceOf(UnsupportedOperationException.class);
    }
    static Stream<String> invalid() {
        String valid=root(row(A,"0.5")+","+row(B,"1"));
        return Stream.of("", "```json\n"+valid+"\n```",valid+" explanation",valid+valid,"prefix "+valid,
                root(row(A,"0.5")),root(row(A,"0.5")+","+row(A,"0.3")),root(row(A,"0.5")+","+row("unknown","1")),
                root(row(A,"\"0.5\"")+","+row(B,"1")),root(row(A,"true")+","+row(B,"1")),root(row(A,"null")+","+row(B,"1")),
                root(row(A,"-0.01")+","+row(B,"1")),root(row(A,"1.01")+","+row(B,"1")),root(row(A,"NaN")+","+row(B,"1")),root(row(A,"1e999")+","+row(B,"1")),
                "{\"scores\":["+row(A,"0.5")+","+row(B,"1")+"],\"extra\":true}",
                root("{\"chunkId\":\""+A+"\",\"score\":0.5,\"extra\":1},"+row(B,"1")),
                root("{\"chunkId\":\""+A+"\",\"score\":0.5,\"score\":0.6},"+row(B,"1")),
                "{\"scores\":["+row(A,"0.5")+","+row(B,"1")+"],\"scores\":["+row(A,"0.5")+","+row(B,"1")+"]}");
    }
    @ParameterizedTest @MethodSource("invalid")
    void rejectsMalformedSchemaMembershipCoercionAndExtraOutput(String json) {
        assertThatThrownBy(()->StrictRerankScores.parse(json,Set.of(A,B))).isInstanceOf(RagProblem.class)
                .extracting("code").isEqualTo("INVALID_RERANK_OUTPUT");
    }
}
