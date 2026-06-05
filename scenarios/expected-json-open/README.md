# expected: json-open

The `json-open` key checks a file's JSON content against an expected value, ignoring any extra object keys present in the actual file.
Openness applies to maps only.
Extra array items are not ignored.

Accepts either a JSON string or a TOML data structure as the expected value.
