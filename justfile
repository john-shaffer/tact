alias b := build
alias fmt := format
alias t := test
alias u := update

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Build the tact package
build:
    nix build

check: test
    nix flake check

# Format source code and data
format:
    just --fmt --unstable -f justfile
    fd -e json -x jsonfmt -w
    fd -e nix -x nixfmt
    fd -e toml -x taplo format
    standard-clj fix

_jar-app-path:
    @nix build .#jar-app --print-out-paths

_nix-system:
    @nix eval --impure --raw --expr 'builtins.currentSystem'

# Run tact
run *args:
    #!/usr/bin/env bash
    PATH="$(just _jar-app-path)/bin:$PATH" tact {{ args }}

# Run all scenarios against native-image binary
test:
    #!/usr/bin/env bash
    OUT=$(nix build .#checks."$(just _nix-system)".scenarios --print-out-paths)

# Run all scenarios against dev jar
test-jar:
    #!/usr/bin/env bash
    PATH="$(just _jar-app-path)/bin:$PATH" tact test

# Update dependencies
update: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
