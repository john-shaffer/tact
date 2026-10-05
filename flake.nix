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
        "x86_64-darwin"
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
        in
        {
          checks.scenarios =
            pkgs.runCommand "tact-check-scenarios" { buildInputs = [ tactBin ] ++ scenarioCheckInputs; }
              ''
                ${tactBin}/bin/tact ${tactScenarios}/scenarios
                touch $out
              '';

          devShells.default = pkgs.mkShell {
            buildInputs =
              with pkgs;
              [
                clojure
                deps-lock
                just
                sand.packages.${system}.default
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
    in
    {
      checks = builtins.mapAttrs (_: outputs: outputs.checks) outputsBySystem;
      devShells = builtins.mapAttrs (_: outputs: outputs.devShells) outputsBySystem;
      packages = builtins.mapAttrs (_: outputs: outputs.packages) outputsBySystem;
    };
}
