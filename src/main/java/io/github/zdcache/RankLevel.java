package io.github.zdcache;

/** How well a result of {@code searchRanked} matched; results come best first, in the order of these levels. */
public enum RankLevel {
    /** The whole value equals the search text, ignoring case and extra spaces. */
    EXACT,
    /** The whole value starts with the search text. */
    STARTS_WITH,
    /** Every query word starts a word of the value, in the query's order. */
    WORDS_IN_ORDER,
    /** Every query word starts a word of the value, but in another order. */
    WORDS_IN_ANY_ORDER,
    /** A contains search found the text inside a word rather than at its start. */
    CONTAINS,
    /** A fuzzy search needed one edit. */
    ONE_EDIT,
    /** A fuzzy search needed two edits. */
    TWO_EDITS
}
