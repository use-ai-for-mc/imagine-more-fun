# Release and local deployment

## Development verification

Run the narrowest relevant test while iterating, then use the repository checks before handoff:

```bash
./gradlew spotlessCheck
./gradlew test
./gradlew build
git diff --check
```

The artifact name is derived from `mod_version` in `gradle.properties`:

```text
build/libs/imaginemorefun-<mod_version>.jar
```

`./gradlew build` packages whatever native binaries currently exist under
`src/main/resources/native/`. It is suitable for Java-side verification, but not sufficient for a
fresh native-helper release.

## PrismLauncher deployment

Only deploy after explicit user authorization:

```bash
./build-and-deploy.sh
```

The local default is the dedicated PrismLauncher `26.2` instance. To select another compatible
instance, pass its game directory explicitly through the environment:

```bash
IMF_PRISM_GAME_DIR="/absolute/path/to/instance/minecraft" ./build-and-deploy.sh
```

Before building or changing any files in the target instance, the script reads `mmc-pack.json` and
refuses to continue unless that instance's Minecraft version matches `minecraft_version` in this
repository.

The script:

1. Rebuilds macOS Swift helpers.
2. Best-effort cross-compiles Windows .NET helpers when `dotnet` is available.
3. Runs Spotless and the Gradle build/test lifecycle.
4. Removes cached native helpers so the new embedded binaries extract on next launch.
5. Removes superseded former-mod and old-version ImagineMoreFun JARs.
6. Copies to a `.new` file in the target directory, verifies the ZIP, and atomically renames it over
   the versioned target JAR.

The default target instance is:

```text
/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/26.2/minecraft/
```

Never use plain `cp` over the active JAR. The atomic same-directory rename preserves the old inode
for a running JVM while making the new inode available to future launches.

## Post-deploy verification

At minimum, compare the source and target hashes and verify the target archive:

```bash
shasum -a 256 \
  build/libs/imaginemorefun-<mod_version>.jar \
  "/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/imaginemorefun-<mod_version>.jar"

unzip -tq \
  "/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/imaginemorefun-<mod_version>.jar"
```

A matching hash proves deployment of the file, not runtime loading. If Minecraft was running during
the swap, it continues using the old open inode. Fully exit and restart the Prism instance before
testing the new JVM classes or re-extracted native helpers.

## GitHub release path

`.github/workflows/build.yml` builds universal macOS helpers and Windows helpers, assembles native
resources, verifies their presence in the JAR, uploads build artifacts, and publishes tagged
releases. Do not describe an ordinary local Gradle JAR as equivalent unless its native helpers were
rebuilt by the deployment/release workflow.
