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
    sand format
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
    set -euo pipefail
    out=$(nix build .#scenario-report --print-out-paths)
    cat "$out/log"
    exit "$(cat "$out/exit-status")"

# Run scenarios against dev jar
test-jar *paths="scenarios":
    #!/usr/bin/env bash
    PATH="$(just _jar-app-path)/bin:$PATH" tact --jobs "$(nproc)" {{ paths }}

# Update dependencies
update: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
