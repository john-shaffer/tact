# Step processes

Each step runs as `bash -c <run>` in the work dir.

stdout and stderr are read concurrently, so a step can write any amount to either.

stdin is the `stdin` string if given, and is closed either way, so commands that read stdin see EOF instead of waiting.
