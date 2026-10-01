# env

The `env` table sets environment variables for steps.
A top-level `env` applies to every step, and a step's own `env` adds to or overrides it for that step only.

```toml
[env]
CACHE_DIR = "${TACT_WORK_DIR}/cache"

[[steps]]
run = "my-tool build"

[steps.env]
LOG_LEVEL = "debug"
```

Values can refer to other variables as `${NAME}`.
Unset variables expand to the empty string.
Top-level values see the environment tact was started with.
Step values also see the top-level `env`.
Values in the same table do not see each other.

Steps always have `TACT_WORK_DIR` set to the absolute path of the work dir.

Values must be strings, numbers or booleans.
