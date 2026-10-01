# Step processes

Each step runs as `bash -c <run>` in the work dir.

stdout and stderr are read concurrently, so a step can write any amount to either.
