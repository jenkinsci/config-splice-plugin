package io.jenkins.plugins.configsplice.engine.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parsed path model.
 *
 * <p>{@link JsonPath#canonical()} is what duplicate-path detection compares, so it has to quote
 * exactly the members that need quoting: quote too little and the rendered path re-parses as
 * something else, quote too much and two spellings of the same path stop looking equal.
 */
class JsonPathTest {

    private static JsonPath path(JsonPath.Step... steps) {
        return new JsonPath(List.of(steps));
    }

    private static JsonPath.Property property(String name) {
        return new JsonPath.Property(name);
    }

    private static JsonPath.Index index(int position) {
        return new JsonPath.Index(position);
    }

    @Nested
    @DisplayName("canonical form")
    class Canonical {

        @Test
        void plainMembersAreNotQuoted() {
            assertEquals("Port", path(property("Port")).canonical());
            assertEquals(
                    "Logging.LogLevel.Default",
                    path(property("Logging"), property("LogLevel"), property("Default")).canonical());
        }

        @Test
        @DisplayName("characters that would re-parse as syntax force quoting")
        void membersNeedingQuotes() {
            assertEquals("'a.b'", path(property("a.b")).canonical());
            assertEquals("'a[0]'", path(property("a[0]")).canonical());
            assertEquals("'a b'", path(property("a b")).canonical());
            assertEquals("'a]b'", path(property("a]b")).canonical());
        }

        @Test
        void anEmptyMemberIsQuoted() {
            assertEquals("''", path(property("")).canonical());
        }

        @Test
        @DisplayName("an embedded quote is doubled")
        void embeddedQuoteIsEscaped() {
            assertEquals("'it''s'", path(property("it's")).canonical());
        }

        @Test
        @DisplayName("characters that are ordinary in the grammar stay unquoted")
        void charactersThatNeedNoQuoting() {
            assertEquals("Bank:Key", path(property("Bank:Key")).canonical());
            assertEquals("some-key", path(property("some-key")).canonical());
            assertEquals("@odata", path(property("@odata")).canonical());
        }

        @Test
        void indexesAttachWithoutASeparatingDot() {
            assertEquals("Services[0].Url", path(property("Services"), index(0), property("Url")).canonical());
            assertEquals("A[0][1]", path(property("A"), index(0), index(1)).canonical());
        }

        @Test
        void toStringMatchesCanonical() {
            JsonPath subject = path(property("a.b"), index(2));
            assertEquals(subject.canonical(), subject.toString());
        }
    }

    @Nested
    @DisplayName("validation and identity")
    class Validation {

        @Test
        void aNegativeIndexIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new JsonPath.Index(-1));
        }

        @Test
        void aNullMemberNameIsRejected() {
            assertThrows(NullPointerException.class, () -> new JsonPath.Property(null));
            assertThrows(NullPointerException.class, () -> new JsonPath(null));
        }

        @Test
        @DisplayName("paths compare structurally, not by the string they were written as")
        void structuralEquality() {
            // This is what lets duplicate-path detection treat Serilog.'Level' and a programmatically
            // built equivalent as the same path.
            assertEquals(path(property("a"), index(0)), path(property("a"), index(0)));
            assertEquals(
                    path(property("a"), index(0)).hashCode(), path(property("a"), index(0)).hashCode());
            assertNotEquals(path(property("a"), index(0)), path(property("a"), index(1)));
            assertNotEquals(path(property("a")), path(property("A")), "matching is case-sensitive");
        }

        @Test
        void stepsAreDefensivelyCopied() {
            ArrayList<JsonPath.Step> mutable = new ArrayList<>();
            mutable.add(property("a"));
            JsonPath subject = new JsonPath(mutable);

            mutable.add(property("b"));

            assertEquals(1, subject.steps().size(), "the path must not see later mutation of the caller's list");
        }

        @Test
        void describeStepsDistinguishesMembersFromIndexes() {
            assertEquals("<a> [0] <b>", path(property("a"), index(0), property("b")).describeSteps());
        }
    }
}
