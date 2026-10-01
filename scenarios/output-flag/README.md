# --output flag

The `--output <file>` flag writes a JSON summary of the run to a file.

## Structure

```json
{
  "summary": {
    "pass": 2,
    "fail": 1,
    "error": 1,
    "skip": 0
  },
  "scenarios": [
    {
      "name": "My scenario",
      "path": "scenarios/my-scenario.toml",
      "status": "pass",
      "checks": [],
      "steps": []
    },
    {
      "name": "Failing scenario",
      "path": "scenarios/failing.toml",
      "status": "fail",
      "checks": [
        {
          "check": "stdout",
          "step": 1,
          "status": "fail",
          "message": "stdout mismatch",
          "expected": "hello\n",
          "actual": "goodbye\n"
        }
      ],
      "steps": [
        {
          "step": 1,
          "run": "echo goodbye",
          "exit": 0,
          "timed-out": false,
          "stdout": "goodbye\n",
          "stderr": ""
        }
      ]
    },
    {
      "path": "scenarios/broken.toml",
      "status": "error",
      "message": "Path must be inside the work dir: ../x",
      "checks": []
    }
  ]
}
```

`checks` contains only the failing checks, and `steps` only the steps with a failing check.
Passing ones are omitted.

A scenario that cannot run, for example because of an unknown key or a path outside the work dir, has status `error` and a `message`.
