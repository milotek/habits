{
  description = "Habit tracker with a GitHub-style activity grid";

  inputs.nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";

  outputs = {
    self,
    nixpkgs,
  }: let
    systems = ["x86_64-linux" "aarch64-linux"];
    forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
  in {
    packages = forAllSystems (pkgs: {
      default = pkgs.callPackage ./package.nix {};
    });

    devShells = forAllSystems (pkgs: {
      default = pkgs.mkShell {
        packages = [pkgs.gradle pkgs.jdk21 pkgs.kotlin-language-server];
      };
    });

    formatter = forAllSystems (pkgs: pkgs.alejandra);

    nixosModules.default = {
      pkgs,
      lib,
      ...
    }: {
      imports = [./module.nix];
      services.habits.package = lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.default;
    };
  };
}
