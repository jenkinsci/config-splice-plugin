package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.configsplice.engine.SourceRange;
import io.jenkins.plugins.configsplice.engine.SplicePlan;
import io.jenkins.plugins.configsplice.engine.xml.DotNetAttributeLocator;
import io.jenkins.plugins.configsplice.engine.xml.GenericXmlLocator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SRS section 12.5: no type that holds a value may expose it through string conversion.
 *
 * <p>Every type here carries either a replacement value or the value currently in the file, and every
 * one of them is a {@code record}, whose generated {@code toString()} prints all its components. The
 * masking overrides are therefore easy to delete by accident — a refactor that regenerates the record,
 * or simply removes a method that looks unused. These tests exist so that deleting one fails the
 * build rather than silently arming a future log statement.
 *
 * <p>{@code String.valueOf} and concatenation are checked alongside {@code toString()} because those
 * are the shapes an accidental disclosure actually takes.
 */
class ValueMaskingTest {

    private static final String SENTINEL = "s3cr3t-sentinel-value";

    private static void assertMasks(String what, Object subject) {
        assertFalse(subject.toString().contains(SENTINEL), what + ": toString() exposes the value");
        assertFalse(("v=" + subject).contains(SENTINEL), what + ": concatenation exposes the value");
        assertFalse(String.valueOf(subject).contains(SENTINEL), what + ": String.valueOf exposes it");
    }

    @Test
    @DisplayName("the replacement value never survives string conversion")
    void replacementMasksItsText() {
        assertMasks(
                "SubstitutionCallable.Replacement",
                new SubstitutionCallable.Replacement(new SourceRange(3, 9), SENTINEL));

        assertMasks("SplicePlan.Edit", new SplicePlan.Edit(new SourceRange(3, 9), SENTINEL, "label"));
    }

    @Test
    @DisplayName("the value already in the file never survives string conversion either")
    void locatedResultsMaskTheCurrentValue() {
        // A config file's existing value is just as sensitive as the one replacing it: the whole
        // reason to substitute a connection string is that the file holds one.
        assertMasks(
                "DotNetAttributeLocator.Located",
                new DotNetAttributeLocator.Located(new SourceRange(3, 9), '"', SENTINEL));

        assertMasks(
                "GenericXmlLocator.Located",
                new GenericXmlLocator.Located(
                        new SourceRange(3, 9), GenericXmlLocator.Located.Kind.ATTRIBUTE, '"', SENTINEL));
    }

    @Test
    @DisplayName("the configuration carrier does not print the values it can reach")
    void configurationMasksItsTargets() {
        // Configuration reaches Substitution.getValue(), which may be a hard-coded literal secret.
        // Today the nested types have no toString() of their own, so a generated one would print
        // identity hashes -- but that is a property of those classes, not a guarantee, and this is
        // the type that would start printing values the day one of them gains a toString().
        Substitution substitution = new Substitution("ApiKey");
        substitution.setValue(SENTINEL);
        TargetGroup group = new TargetGroup(List.of("appsettings.json"), List.of(substitution));

        assertMasks(
                "SubstitutionRunner.Configuration",
                new SubstitutionRunner.Configuration(
                        List.of(group), false, "fail", "fail", false));
    }

    @Test
    @DisplayName("masking still says enough to debug with")
    void maskedFormsRemainUseful() {
        // Masking is only sustainable if it does not make diagnostics useless; the offsets are the
        // part worth keeping, and they carry no information about the value itself.
        String rendered = new SubstitutionCallable.Replacement(new SourceRange(3, 9), SENTINEL).toString();
        assertTrue(rendered.contains("3") && rendered.contains("9"), "offsets should survive: " + rendered);
    }
}
