package io.jenkins.plugins.configsplice.engine;

/**
 * User-facing error categories from SRS section 15.3.
 *
 * <p>Every failure surfaced to a Jenkins build is classified by one of these codes. The code is the
 * stable part of the contract; the accompanying message is explanatory and must always be
 * <em>value-free</em> (SRS section 12.5): it may name files, property paths and offsets, but never
 * current values, replacement values, credential IDs or source excerpts.
 */
public enum ErrorCode {
    PATH_SYNTAX,

    /**
     * No longer reachable from a step invocation.
     *
     * <p>Since SRS section 8.7 added generic XML paths, every XML path is either a shorthand or a
     * generic path, so nothing is left to be "unsupported". Retained because the code is part of the
     * documented contract of section 15.3 and is still produced by
     * {@code DotNetPathParser} when that parser is called directly with a non-shorthand path.
     */
    XML_PATH_UNSUPPORTED,
    TARGET_GROUP_OVERLAP,
    FILE_NOT_FOUND,
    PATH_MISSING,
    PATH_AMBIGUOUS,
    NON_SCALAR,
    TYPE_INVALID,
    CREDENTIAL_TYPE_ACK_REQUIRED,
    DUPLICATE_JSON_KEY,
    CREDENTIAL_UNAVAILABLE,
    UNSUPPORTED_ENCODING,
    WORKSPACE_ESCAPE,
    PARSE_FAILED,
    SOURCE_CHANGED,
    WRITE_FAILED;

    /** Returns the wire form used in logs and documentation, e.g. {@code CONFIG_SUBSTITUTION_PATH_SYNTAX}. */
    public String wireName() {
        return "CONFIG_SUBSTITUTION_" + name();
    }
}
