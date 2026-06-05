# expected: json

The `json` key checks a file's JSON content against an expected value with exact semantic equality.
All object keys must match, but key ordering is ignored.

Accepts either a JSON string or a TOML data structure as the expected value.
