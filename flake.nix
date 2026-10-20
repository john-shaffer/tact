{
  description = "tact scenario runner";

  inputs = {
    # Latest stable nixpkgs
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0";
    clj-nix = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:jlesquembre/clj-nix";
    };
    sand = {
      inputs.clj-nix.follows = "clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/sand";
    };
  };

  outputs =
    inputs:
    with inputs;
    let
      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];
      outputsBySystem = nixpkgs.lib.genAttrs systems (
        system:
        with import nixpkgs {
          inherit system;
          overlays = [ clj-nix.overlays.default ];
        };
        let
          version = "0.1.0";
          jdkPackage = pkgs.graalvmPackages.graalvm-ce; # Provides up-to-date JDK and native-image binaries
          lockfile = lib.sources.sourceByRegex self [ "^deps-lock.json$" ];
          tactSrc = lib.sources.sourceFilesBySuffices self [
            ".clj"
            ".edn"
          ];
          tactScenarios = lib.sources.sourceFilesBySuffices self [ ".toml" ];
          tactCljModule = {
            jdk = jdkPackage;
            lockfile = lockfile + /deps-lock.json;
            main-ns = "tact.cli";
            name = "tact";
            projectSrc = tactSrc;
            version = version;
          };
          tactJarApp = clj-nix.lib.mkCljApp {
            pkgs = nixpkgs.legacyPackages.${system};
            modules = [
              tactCljModule
            ];
          };
          tactBin = clj-nix.lib.mkCljApp {
            pkgs = nixpkgs.legacyPackages.${system};
            modules = [
              tactCljModule
              {
                nativeImage.enable = true;
              }
            ];
          };
          scenarioCheckInputs = with pkgs; [
            coreutils
            jq
            jsonfmt
          ];
          # Always succeeds, so Nix caches the result of a failing run too
          scenarioReport =
            pkgs.runCommand "tact-scenario-report" { buildInputs = [ tactBin ] ++ scenarioCheckInputs; }
              ''
                mkdir -p $out
                jobs=''${NIX_BUILD_CORES:-1}
                [ "$jobs" -gt 0 ] || jobs=$(nproc)
                status=0
                tact --jobs "$jobs" --output $out/report.json ${tactScenarios}/scenarios > $out/log 2>&1 || status=$?
                echo "$status" > $out/exit-status
              '';
        in
        {
          checks.scenarios = pkgs.runCommand "tact-check-scenarios" { } ''
            if [ "$(cat ${scenarioReport}/exit-status)" != 0 ]; then
              cat ${scenarioReport}/log >&2
              exit 1
            fi
            touch $out
          '';

          devShells.default = pkgs.mkShell {
            buildInputs =
              with pkgs;
              [
                clojure
                deps-lock
                just
                nixfmt
                sand.packages.${system}.default
                taplo
              ]
              ++ scenarioCheckInputs;
            shellHook = ''
              echo
              echo -e "Run '\033[1mjust <recipe>\033[0m' to get started"
              just --list
            '';
          };

          packages.default = tactBin;
          packages.jar-app = tactJarApp;
          packages.scenario-report = scenarioReport;
        }
      );
    in
    {
      checks = builtins.mapAttrs (_: outputs: outputs.checks) outputsBySystem;
      devShells = builtins.mapAttrs (_: outputs: outputs.devShells) outputsBySystem;
      packages = builtins.mapAttrs (_: outputs: outputs.packages) outputsBySystem;
    };
}
