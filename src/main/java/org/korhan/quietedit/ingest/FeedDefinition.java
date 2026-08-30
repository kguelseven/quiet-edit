package org.korhan.quietedit.ingest;

/**
 * One entry of the feed catalogue file. Deliberately not the {@link Feed} entity:
 * the file is the operator's intent, the entity is observed state (etag, last
 * poll, status), and mixing the two would let a catalogue reload wipe state.
 */
public record FeedDefinition(String url, String name) {
}
