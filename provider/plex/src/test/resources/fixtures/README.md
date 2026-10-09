# Plex fixtures

**Synthetic.** These were written by hand to match the documented shape of Plex Media Server JSON responses
(`Accept: application/json`). They are not recordings. Replace each with a sanitized recording from a real server
when one is available; keep the file names, since `PlexFixtureServer` maps request paths to them.

To record: `curl -H 'Accept: application/json' -H 'X-Plex-Token: ...' 'http://server:32400/library/sections' | jq .`
then remove tokens, file paths, and anything personal before committing.
