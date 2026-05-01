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
        jdkPackage = pkgs.jdk25_headless;
        lockfile = lib.sources.sourceByRegex self [ "^deps-lock.json$" ];
        tactSrc = lib.sources.sourceFilesBySuffices self [
          ".clj"
          ".edn"
        ];
        tactBin = clj-nix.lib.mkCljApp {
          pkgs = nixpkgs.legacyPackages.${system};
          modules = [
            {
              jdk = jdkPackage;
              lockfile = lockfile + /deps-lock.json;
              main-ns = "tact.cli";
              name = "tact";
              nativeImage.enable = true;
              projectSrc = tactSrc;
              version = "0.1.0";
            }
          ];
        };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            clojure
            deps-lock
            jsonfmt
            just
            nixfmt
            taplo
          ];
          shellHook = ''
            echo
            echo -e "Run '\033[1mjust <recipe>\033[0m' to get started"
            just --list
          '';
        };
        packages.default = tactBin;
      }
    );
}
