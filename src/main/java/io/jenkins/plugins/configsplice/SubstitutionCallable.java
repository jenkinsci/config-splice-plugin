package io.jenkins.plugins.configsplice;

import hudson.AbortException;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import io.jenkins.plugins.configsplice.engine.AtomicFileWriter;
import io.jenkins.plugins.configsplice.engine.EncodingSupport;
import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SourceDocument;
import io.jenkins.plugins.configsplice.engine.SourceRange;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import io.jenkins.plugins.configsplice.engine.SplicePlan;
import io.jenkins.plugins.configsplice.engine.WorkspaceGuard;
import io.jenkins.plugins.configsplice.engine.json.JsonPath;
import io.jenkins.plugins.configsplice.engine.json.JsonPathParser;
import io.jenkins.plugins.configsplice.engine.json.JsonScalarLocator;
import io.jenkins.plugins.configsplice.engine.json.JsonStrings;
import io.jenkins.plugins.configsplice.engine.xml.DotNetAttributeLocator;
import io.jenkins.plugins.configsplice.engine.xml.DotNetPath;
import io.jenkins.plugins.configsplice.engine.xml.DotNetPathParser;
import io.jenkins.plugins.configsplice.engine.xml.GenericXmlLocator;
import io.jenkins.plugins.configsplice.engine.xml.XmlAttributes;
import io.jenkins.plugins.configsplice.engine.xml.XmlPath;
import io.jenkins.plugins.configsplice.engine.xml.XmlPathParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jenkins.agents.ControllerToAgentFileCallable;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;

/**
 * Runs the whole substitution on the agent that owns the workspace (SRS section 12.4).
 *
 * <p>Everything happens here: glob expansion, confinement, parsing, locating, planning and writing.
 * File contents never travel to the controller. What comes back is the value-free result map plus a
 * list of log lines for the controller to print, so that log ordering is deterministic and no
 * {@code TaskListener} plumbing is needed agent-side.
 *
 * <p>The return type is built from JDK collections only. That is what makes it JEP-200-safe over
 * remoting and, once handed to the step, consumable from a sandboxed Pipeline without Script
 * Approval (SRS section 11.2).
 */
final class SubstitutionCallable implements ControllerToAgentFileCallable<HashMap<String, Object>> {

    private static final long serialVersionUID = 1L;

    /** Statuses used in {@code details}; a stable part of the public API. */
    private static final String PLANNED = "planned";

    private static final String CHANGED = "changed";

    private static final String UNCHANGED = "unchanged";

    private static final String MISSING = "missing";

    /**
     * Test seam required by SRS section 13.4.
     *
     * <p>The source-change check is only meaningful if a test can mutate the file in the window
     * between planning and verification, and that window is otherwise unreachable from outside. The
     * hook is package-private, absent from the Pipeline API, and {@code null} in production, so it
     * has no effect unless a test installs one.
     */
    interface PreCommitHook {
        void beforeCommit(Path file) throws IOException;
    }

    private static volatile PreCommitHook preCommitHook;

    static void setPreCommitHookForTests(PreCommitHook hook) {
        preCommitHook = hook;
    }

    private final SubstitutionRequest request;

    SubstitutionCallable(SubstitutionRequest request) {
        this.request = request;
    }

    @Override
    public HashMap<String, Object> invoke(File workspaceDir, VirtualChannel channel)
            throws IOException, InterruptedException {
        try {
            return run(workspaceDir.toPath());
        } catch (SpliceException e) {
            // AbortException is an IOException, so this still crosses the channel unchanged, but both
            // surfaces treat it as a clean failure: the already value-free message is shown on its
            // own, with no stack trace. The cause is dropped deliberately -- a cause chain is exactly
            // where a source excerpt the message withholds could resurface.
            throw new AbortException(e.getMessage());
        }
    }

    private HashMap<String, Object> run(Path workspace) throws SpliceException {
        Report report = new Report();
        Map<String, SubstitutionRequest.Group> owners = new TreeMap<>();

        // ---- discovery -----------------------------------------------------------------
        for (SubstitutionRequest.Group group : request.groups) {
            Set<String> union = new LinkedHashSet<>();
            for (String glob : group.globs) {
                List<String> matched = expand(workspace, glob);
                report.pattern(group.index, glob, matched.size());
                if (matched.isEmpty() && !group.globs.isEmpty()) {
                    report.log("NOTE: target group " + group.index + ": pattern '" + glob
                            + "' matched no files.");
                }
                union.addAll(matched);
            }
            if (union.isEmpty()) {
                applyNoMatch(group, report);
                continue;
            }
            for (String relative : union) {
                SubstitutionRequest.Group previous = owners.putIfAbsent(relative, group);
                if (previous != null && previous != group) {
                    throw new SpliceException(
                            ErrorCode.TARGET_GROUP_OVERLAP,
                            "file '" + relative + "' is matched by target groups " + previous.index
                                    + " and " + group.index
                                    + "; consolidate them or use non-overlapping patterns");
                }
            }
        }
        report.filesMatched = owners.size();

        // ---- plan ----------------------------------------------------------------------
        List<PlannedFile> planned = new ArrayList<>();
        for (Map.Entry<String, SubstitutionRequest.Group> entry : owners.entrySet()) {
            planned.add(plan(workspace, entry.getKey(), entry.getValue(), report));
        }

        // ---- commit --------------------------------------------------------------------
        if (!request.dryRun) {
            List<String> committed = new ArrayList<>();
            for (PlannedFile file : planned) {
                if (file.plan.isEmpty()) {
                    continue;
                }
                try {
                    PreCommitHook hook = preCommitHook;
                    if (hook != null) {
                        hook.beforeCommit(file.absolute);
                    }
                    verifyUnchangedSincePlanning(file);
                    AtomicFileWriter.replace(file.absolute, file.document.render(file.splicedText));
                    committed.add(file.relative);
                    report.filesChanged++;
                    file.markCommitted(report);
                } catch (SpliceException e) {
                    throw partialCommitFailure(e, file.relative, committed);
                } catch (IOException e) {
                    throw new SpliceException(
                            ErrorCode.WRITE_FAILED, "could not commit '" + file.relative + "'", e);
                }
            }
        }

        return report.toMap(request.dryRun);
    }

    // -------------------------------------------------------------------------------------

    private PlannedFile plan(
            Path workspace, String relative, SubstitutionRequest.Group group, Report report)
            throws SpliceException {

        Path absolute = WorkspaceGuard.requireConfinedRegularFile(workspace, relative);
        byte[] original = readAllBytes(absolute, relative);
        SourceDocument document = SourceDocument.of(original);

        Wire.Format format = effectiveFormat(group.format, relative, document.text());
        if (format == Wire.Format.XML) {
            EncodingSupport.requireSupportedXmlDeclaration(document.text());
        }

        SplicePlan.Builder builder = SplicePlan.builder();
        List<String[]> statuses = new ArrayList<>();

        for (SubstitutionRequest.Sub substitution : group.substitutions) {
            try {
                Replacement replacement = format == Wire.Format.JSON
                        ? locateJson(document.text(), substitution)
                        : locateXml(document.text(), substitution);

                String current = document.text()
                        .substring(replacement.range.start(), replacement.range.end());
                if (current.equals(replacement.text)) {
                    // Idempotency: nothing to do, and the file must not be rewritten (SRS 13.3).
                    statuses.add(new String[] {substitution.path, UNCHANGED});
                } else {
                    builder.add(replacement.range, replacement.text, substitution.path);
                    statuses.add(new String[] {substitution.path, PLANNED});
                }
                report.substitutionsMatched++;
            } catch (SpliceException e) {
                if (e.code() != ErrorCode.PATH_MISSING) {
                    throw e;
                }
                report.substitutionsMissing++;
                statuses.add(new String[] {substitution.path, MISSING});
                applyMissingPath(relative, substitution.path, report);
            }
        }

        SplicePlan plan = builder.build();
        PlannedFile file =
                new PlannedFile(relative, absolute, document, plan, plan.applyTo(document.text()), statuses);

        if (plan.isEmpty()) {
            report.filesUnchanged++;
        } else {
            report.filesPlanned++;
        }
        report.groupOf.put(relative, group.index);
        report.plannedFiles.add(file);
        return file;
    }

    private Replacement locateJson(String text, SubstitutionRequest.Sub substitution)
            throws SpliceException {
        JsonPath path = JsonPathParser.parse(substitution.path);
        JsonScalarLocator.Located located = JsonScalarLocator.locate(text, path);
        return new Replacement(located.range(), JsonValues.serialise(substitution, located.kind()));
    }

    /**
     * Resolves an XML path, dispatching on its leading step (SRS section 6.1 rule 2).
     *
     * <p>A path beginning {@code appSettings.} or {@code connectionStrings.} is .NET shorthand, where
     * the remainder is one literal key. Anything else is a generic element path starting at the
     * document element.
     *
     * <p>The test is {@link DotNetPathParser#isShorthand} — the same predicate that parser uses to
     * claim a path — so routing and parsing can never disagree about who owns a path. It is purely
     * lexical and runs before the document is read, which is what guarantees that no file content can
     * change how a path is interpreted.
     *
     * <p>The shorthand wins the prefix outright: in a document whose <em>own</em> document element is
     * named {@code appSettings}, that element is not reachable generically. That is a deliberate
     * trade for stable routing, and costs nothing on the .NET configuration files this addresses,
     * whose document element is always {@code configuration}.
     */
    private Replacement locateXml(String text, SubstitutionRequest.Sub substitution)
            throws SpliceException {
        if (!substitution.type.validForXml()) {
            throw new SpliceException(
                    ErrorCode.TYPE_INVALID,
                    "path '" + substitution.path
                            + "' uses a type that does not apply to XML; only 'auto' and 'string' are valid");
        }
        String plain = substitution.value == null ? "" : substitution.value.plainText();

        if (DotNetPathParser.isShorthand(substitution.path)) {
            DotNetPath path = DotNetPathParser.parse(substitution.path);
            DotNetAttributeLocator.Located located = DotNetAttributeLocator.locate(text, path);
            return new Replacement(located.range(), XmlAttributes.encode(plain, located.quote()));
        }

        XmlPath path = XmlPathParser.parse(substitution.path);
        GenericXmlLocator.Located located = GenericXmlLocator.locate(text, path);
        String replacement = located.kind() == GenericXmlLocator.Located.Kind.TEXT
                ? XmlAttributes.encodeText(plain)
                : XmlAttributes.encode(plain, located.quote());
        return new Replacement(located.range(), replacement);
    }

    /** Serialises a replacement as a JSON literal according to SRS sections 7.2 and 7.3. */
    private static final class JsonValues {

        static String serialise(SubstitutionRequest.Sub substitution, JsonScalarLocator.ScalarKind existing)
                throws SpliceException {

            Wire.ValueType type = substitution.type;
            if (type == Wire.ValueType.NULL) {
                return "null";
            }
            String plain = substitution.value.plainText();
            boolean credential = substitution.value.fromCredential();

            if (type == Wire.ValueType.AUTO) {
                if (credential) {
                    // SRS 7.3: a hidden value must not silently change a property's semantic type.
                    if (existing != JsonScalarLocator.ScalarKind.STRING) {
                        throw new SpliceException(
                                ErrorCode.CREDENTIAL_TYPE_ACK_REQUIRED,
                                "path '" + substitution.path + "' targets a non-string JSON scalar; "
                                        + "set type: 'string' to acknowledge the type change");
                    }
                    return JsonStrings.encode(plain);
                }
                type = inferFrom(existing, substitution.path);
            }

            return switch (type) {
                case STRING -> JsonStrings.encode(plain);
                case NUMBER -> requireJsonNumber(plain, substitution.path);
                case BOOLEAN -> requireBoolean(plain, substitution.path);
                default -> throw new SpliceException(
                        ErrorCode.TYPE_INVALID, "unsupported type for path '" + substitution.path + "'");
            };
        }

        private static Wire.ValueType inferFrom(JsonScalarLocator.ScalarKind existing, String path)
                throws SpliceException {
            return switch (existing) {
                case STRING -> Wire.ValueType.STRING;
                case NUMBER -> Wire.ValueType.NUMBER;
                case BOOLEAN -> Wire.ValueType.BOOLEAN;
                // Null carries no type to infer, so the user must state one (SRS 7.2).
                case NULL -> throw new SpliceException(
                        ErrorCode.TYPE_INVALID,
                        "path '" + path + "' currently holds null; an explicit type is required");
            };
        }

        private static String requireJsonNumber(String literal, String path) throws SpliceException {
            // Deliberately strict: reject NaN, Infinity, leading '+', and partial parses.
            if (!literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) {
                throw new SpliceException(
                        ErrorCode.TYPE_INVALID,
                        "path '" + path + "' expects a JSON number but the value is not one");
            }
            return literal;
        }

        private static String requireBoolean(String literal, String path) throws SpliceException {
            String normalised = literal.toLowerCase(Locale.ROOT);
            if (!"true".equals(normalised) && !"false".equals(normalised)) {
                throw new SpliceException(
                        ErrorCode.TYPE_INVALID,
                        "path '" + path + "' expects true or false but the value is neither");
            }
            return normalised;
        }
    }

    // -------------------------------------------------------------------------------------

    private void verifyUnchangedSincePlanning(PlannedFile file) throws SpliceException {
        byte[] current = readAllBytes(file.absolute, file.relative);
        if (!Arrays.equals(current, file.document.originalBytes())) {
            throw new SpliceException(
                    ErrorCode.SOURCE_CHANGED,
                    "'" + file.relative + "' changed on disk between planning and commit");
        }
    }

    private SpliceException partialCommitFailure(
            SpliceException cause, String failed, List<String> committed) {
        if (committed.isEmpty()) {
            return cause;
        }
        return new SpliceException(
                ErrorCode.WRITE_FAILED,
                "commit failed on '" + failed + "' after already committing "
                        + String.join(", ", committed)
                        + "; those files now hold substituted content while '" + failed
                        + "' retains its original bytes",
                cause);
    }

    private void applyNoMatch(SubstitutionRequest.Group group, Report report) throws SpliceException {
        String message = "target group " + group.index + " matched no files";
        switch (request.noMatchBehavior) {
            case FAIL -> throw new SpliceException(ErrorCode.FILE_NOT_FOUND, message);
            case WARN -> {
                report.log("WARNING: " + message + ".");
                report.warnings++;
            }
            case IGNORE -> {
                // Counted through patternsUnmatched only.
            }
        }
    }

    private void applyMissingPath(String relative, String path, Report report) throws SpliceException {
        String message = relative + ": path '" + path + "' was not found";
        switch (request.missingPathBehavior) {
            case FAIL -> throw new SpliceException(ErrorCode.PATH_MISSING, message);
            case WARN -> {
                report.log("WARNING: " + message + ".");
                report.warnings++;
            }
            case IGNORE -> {
                // Counted through substitutionsMissing only.
            }
        }
    }

    private Wire.Format effectiveFormat(Wire.Format declared, String relative, String text)
            throws SpliceException {
        if (declared != Wire.Format.AUTO) {
            return declared;
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return Wire.Format.JSON;
        }
        if (lower.endsWith(".xml") || lower.endsWith(".config")) {
            return Wire.Format.XML;
        }
        // Content probe: the first non-whitespace character decides.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '{') {
                return Wire.Format.JSON;
            }
            if (c == '<') {
                return Wire.Format.XML;
            }
            break;
        }
        throw new SpliceException(
                ErrorCode.PARSE_FAILED,
                "could not determine the format of '" + relative + "'; set format explicitly");
    }

    private static List<String> expand(Path workspace, String glob) throws SpliceException {
        if (glob.contains("..") || new File(glob).isAbsolute()) {
            throw new SpliceException(
                    ErrorCode.WORKSPACE_ESCAPE, "file pattern must be workspace-relative: '" + glob + "'");
        }
        FileSet fileSet = Util.createFileSet(workspace.toFile(), glob);
        DirectoryScanner scanner = fileSet.getDirectoryScanner(new Project());
        String[] included = scanner.getIncludedFiles();
        List<String> normalised = new ArrayList<>(included.length);
        for (String each : included) {
            normalised.add(each.replace(File.separatorChar, '/'));
        }
        normalised.sort(String::compareTo);
        return normalised;
    }

    private static byte[] readAllBytes(Path path, String relative) throws SpliceException {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new SpliceException(ErrorCode.WRITE_FAILED, "could not read '" + relative + "'", e);
        }
    }

    // -------------------------------------------------------------------------------------

    /**
     * A located range and the already-encoded text to put in it.
     *
     * <p>{@code text} is the replacement value, which for a credential-backed substitution is the
     * secret in the clear. The generated record {@code toString()} would print it, so it is
     * overridden to mask — the same rule {@link SplicePlan.Edit} follows, and the reason
     * {@link ResolvedValue} exists. Nothing currently logs a {@code Replacement}; this is here so
     * that adding a log line later cannot quietly turn into a disclosure.
     *
     * <p>Package-private rather than private only so {@code ValueMaskingTest} can assert the masking.
     */
    record Replacement(SourceRange range, String text) {

        @Override
        public String toString() {
            return "Replacement[@" + range.start() + ".." + range.end() + ", text hidden]";
        }
    }

    /** A file with its plan computed but not yet committed. */
    private static final class PlannedFile {

        final String relative;

        final Path absolute;

        final SourceDocument document;

        final SplicePlan plan;

        final String splicedText;

        final List<String[]> statuses;

        PlannedFile(
                String relative,
                Path absolute,
                SourceDocument document,
                SplicePlan plan,
                String splicedText,
                List<String[]> statuses) {
            this.relative = relative;
            this.absolute = absolute;
            this.document = document;
            this.plan = plan;
            this.splicedText = splicedText;
            this.statuses = statuses;
        }

        void markCommitted(Report report) {
            for (String[] status : statuses) {
                if (PLANNED.equals(status[1])) {
                    status[1] = CHANGED;
                }
            }
        }
    }

    /** Accumulates counters, detail records and log lines, then renders the sandbox-safe map. */
    private static final class Report {

        final List<String> log = new ArrayList<>();

        final List<Map<String, Object>> patterns = new ArrayList<>();

        final List<PlannedFile> plannedFiles = new ArrayList<>();

        final Map<String, Integer> groupOf = new HashMap<>();

        int patternsEvaluated;

        int patternsUnmatched;

        int filesMatched;

        int filesPlanned;

        int filesChanged;

        int filesUnchanged;

        int substitutionsMatched;

        int substitutionsMissing;

        int warnings;

        void log(String line) {
            log.add(line);
        }

        void pattern(int group, String glob, int matches) {
            patternsEvaluated++;
            if (matches == 0) {
                patternsUnmatched++;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", "pattern");
            entry.put("group", group);
            entry.put("pattern", glob);
            entry.put("file", null);
            entry.put("path", null);
            entry.put("status", matches == 0 ? "unmatched" : "matched");
            entry.put("matches", matches);
            patterns.add(entry);
        }

        HashMap<String, Object> toMap(boolean dryRun) {
            List<Object> details = new ArrayList<>(patterns);
            for (PlannedFile file : plannedFiles) {
                for (String[] status : file.statuses) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("kind", "substitution");
                    entry.put("group", groupOf.getOrDefault(file.relative, 0));
                    entry.put("pattern", null);
                    entry.put("file", file.relative);
                    entry.put("path", status[0]);
                    entry.put("status", status[1]);
                    entry.put("matches", null);
                    details.add(entry);
                }
            }

            HashMap<String, Object> result = new LinkedHashMap<>();
            result.put("dryRun", dryRun);
            result.put("targetGroups", groupCount());
            result.put("patternsEvaluated", patternsEvaluated);
            result.put("patternsUnmatched", patternsUnmatched);
            result.put("filesMatched", filesMatched);
            result.put("filesPlanned", filesPlanned);
            result.put("filesChanged", filesChanged);
            result.put("filesUnchanged", filesUnchanged);
            result.put("substitutionsMatched", substitutionsMatched);
            result.put("substitutionsMissing", substitutionsMissing);
            result.put("warnings", warnings);
            result.put("details", new ArrayList<>(details));
            result.put("log", new ArrayList<>(log));
            return result;
        }

        private int groupCount() {
            return (int) patterns.stream().map(entry -> entry.get("group")).distinct().count();
        }
    }
}
