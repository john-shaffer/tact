# Step processes

Each step runs as `bash -c <run>` in the work dir.

stdout and stderr are read concurrently, so a step can write any amount to either.

stdin is the `stdin` string if given, and is closed either way, so commands that read stdin see EOF instead of waiting.

A step that runs longer than its timeout is killed, along with its child processes, and fails with a `timeout` check.

## Timeout

The timeout is in seconds. The first one set wins:

1. `timeout` on the step
2. `timeout` at the top level of the scenario
3. `--timeout` on the command line
4. 600 seconds
