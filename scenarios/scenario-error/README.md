# Scenario errors

A scenario that cannot run, for example because its TOML is invalid, has status `error`.
tact prints the error, runs the remaining scenarios, and exits with status 1.
