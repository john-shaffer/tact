# --output flag

The `--output <file>` flag writes a JSON summary of the run to a file.

## Structure

```json
{
  "summary": {
    "pass": 2,
    "fail": 1,
    "error": 0,
    "skip": 0
  },
  "scenarios": [
    {
      "name": "My scenario",
      "path": "scenarios/my-scenario.toml",
      "status": "pass",
      "checks": []
    },
    {
      "name": "Failing scenario",
      "path": "scenarios/failing.toml",
      "status": "fail",
      "checks": [
        {
          "check": "stdout",
          "status": "fail",
          "message": "stdout mismatch",
          "expected": "hello\n",
          "actual": "goodbye\n"
        }
      ]
    }
  ]
}
```

`checks` contains only the failing checks. Passing checks are omitted.
