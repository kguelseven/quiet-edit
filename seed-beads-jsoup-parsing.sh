#!/usr/bin/env bash
set -euo pipefail

mk() { # $1=title $2=type $3=priority, description from stdin
  local d; d=$(cat)
  bd create "$1" -t "$2" -p "$3" --description="$d" --json | jq -r '.id'
}

NEW=$(mk "Parse feed in a single jsoup pass" task 0 <<'EOF'
Goal: parse feeds in a single pass with jsoup, so that structure and raw
field values come from the same traversal.

Background: the current parser runs rome first for the entry structure,
then a second jsoup pass to recover the original date strings that rome
discards, matching the two by document position. That holds only while
both parsers see the same entries in the same order. Rome drops entries
without a link; jsoup does not. The count guard catches the mismatch but
cannot repair it, and the arrangement is hard to reason about.

Acceptance:
- FeedParser walks the XML once with jsoup, using Parser.xmlParser() so
  case and namespace prefixes are preserved, and builds FeedEntry directly.
  RSS 2.0 and Atom both supported, callers see no difference.
- Both the parsed value and the verbatim text as published survive for
  every date field; no positional matching anywhere.
- Rome is removed from the parser and from pom.xml.
- The existing FeedParserTest passes unchanged, including the malformed
  and incomplete-entry fixtures. Behaviour is identical from the outside.
  If a test has to be adapted to go green, stop and say so in the handoff
  rather than adapting it.
- Never throws: an unparseable body yields a failed result, as before.

Boundary: no new fields on FeedEntry, no encoding work (that ticket owns
it), no change to how the fetcher calls the parser.
Flyway: none.
EOF
)

bd dep add "$NEW" quietedit-10i --type parent-child   # Ingest epic
bd dep add quietedit-m4p "$NEW"                        # date normalisation waits
bd show "$NEW"
