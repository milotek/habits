{
  lib,
  stdenv,
  gradle,
  jre_headless,
  makeBinaryWrapper,
}:
stdenv.mkDerivation (finalAttrs: {
  pname = "habits";
  version = "0.1.0";

  src = ./.;

  nativeBuildInputs = [
    gradle
    makeBinaryWrapper
  ];

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  gradleBuildTask = "installDist";
  doCheck = false;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/habits
    cp -r build/install/habits/lib $out/share/habits/lib

    makeBinaryWrapper ${lib.getExe jre_headless} $out/bin/habits \
      --add-flags "-cp $out/share/habits/lib/*" \
      --add-flags rip.tek.habits.MainKt

    runHook postInstall
  '';

  meta = {
    description = "Habit tracker with a GitHub-style activity grid";
    license = lib.licenses.mit;
    mainProgram = "habits";
    platforms = jre_headless.meta.platforms;
  };
})
