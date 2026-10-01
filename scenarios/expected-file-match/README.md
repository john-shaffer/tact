# expected: file existence and matching

- `exists = false` checks that the file does not exist. It cannot be combined with other assertions.
- `exists = true`, or an empty table, checks only that the file exists. `exists` must be a boolean.
- `contains`, `not-contains` and `matches` check part of the file content, with the same rules as [`stdout-contains` and friends](../expected-output-match/README.md).

Any other assertion also fails when the file does not exist.

```toml
[steps.expected.files.".sand/sand.json"]
contains = '"taplo"'
not-contains = ["no-such-package", "outdated"]
```
