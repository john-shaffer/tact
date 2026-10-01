# Failure details

When any check of a step fails, tact prints the step's number, exit code, command, stdout and stderr after its checks.
Long output is cut to its last 40 lines.

With `--output`, failing steps are also listed in the JSON summary.
See [--output](../output-flag/README.md).
