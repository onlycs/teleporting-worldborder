{
  description = "Elastic Worldborder Development Environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };

        applications = with pkgs; [
          nil
          nixd

          temurin-bin-25
          gradle
          jdt-language-server
        ];

        libraries = with pkgs; [
          pkg-config

          wayland
          libxkbcommon

          libGL
          vulkan-loader
          vulkan-headers
          udev

          openssl
          gcc.cc.lib
          stdenv.cc.cc.lib

          libX11
          libXcursor
          libXrandr
          libXi
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = applications ++ libraries;

          OPENSSL_DIR = pkgs.openssl.dev;
          OPENSSL_LIB_DIR = "${pkgs.openssl.out}/lib";
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath libraries;
          PKG_CONFIG_PATH = pkgs.lib.makeSearchPath "lib/pkgconfig" libraries;
        };
      }
    );
}
