package dev.myfrei.repopilot.rerank;

import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.deser.std.StdDeserializer;

/** Local API rule: do not silently coerce 1.9 or "2" to an integer keep. */
public class StrictKeepDeserializer extends StdDeserializer<Integer> {
    public StrictKeepDeserializer() { super(Integer.class); }
    @Override public Integer deserialize(JsonParser parser,DeserializationContext context) {
        if (parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("Keep must be an integer");
        return parser.getIntValue();
    }
}
