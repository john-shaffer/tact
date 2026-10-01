# Path confinement

Paths under `setup.files` and `expected.files` are relative to the work dir and must stay inside it.
A path that is absolute or uses `..` to leave the work dir makes the scenario an error.
