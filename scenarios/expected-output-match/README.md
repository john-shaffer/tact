# expected: stdout and stderr matching

`stdout` and `stderr` check output with exact string equality.
These keys check part of it instead:

- `stdout-contains`, `stderr-contains`: the output contains the text.
- `stdout-not-contains`, `stderr-not-contains`: the output does not contain the text.
- `stdout-matches`, `stderr-matches`: a Java regular expression is found in the output.
  `^` and `$` match at line boundaries, like `grep`.
  Add `(?s)` for `.` to match newlines too.

Each accepts a string or an array of strings, all of which must pass.

```toml
[steps.expected]
exit-code = 127
stderr-matches = "^error: command not found: foo$"
```
