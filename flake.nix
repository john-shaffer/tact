{
  description = "tact scenario runner";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.11";
    clj-nix = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:jlesquembre/clj-nix";
    };
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    inputs:
    with inputs;
    flake-utils.lib.eachDefaultSystem (
      system:
      with import nixpkgs {
        inherit system;
        overlays = [ clj-nix.overlays.default ];
      };
      let
        version = "0.1.0";
        jdkPackage = pkgs.jdk25_headless;
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
      in
      {
        checks.scenarios =
          pkgs.runCommand "tact-check-scenarios" { buildInputs = [ tactBin ] ++ scenarioCheckInputs; }
            ''
              ${tactBin}/bin/tact ${tactScenarios}/test
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
      }
    );
}
