"""Bundle signed aiClient publications in Maven repository layout."""

import re
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


GROUP_PATH = Path("in/hrup/aiclient")
TARGETS = {
    "": ".jar",
    "-jvm": ".jar",
    "-android": ".aar",
    "-iosarm64": ".klib",
    "-iossimulatorarm64": ".klib",
}


def build_bundle(staging, version, destination):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]*", version) or version.upper().endswith("-SNAPSHOT"):
        raise SystemExit("Maven Central requires a release version.")

    paths = []
    for module in ("core", "kmp"):
        for target, extension in TARGETS.items():
            artifact = module + target
            folder = staging / GROUP_PATH / artifact / version
            base = f"{artifact}-{version}"
            required = (".pom", ".module", extension, "-sources.jar", "-javadoc.jar")
            for suffix in required:
                if not (folder / (base + suffix)).is_file():
                    raise SystemExit(f"Missing publication file: {folder / (base + suffix)}")

            files = sorted(path for path in folder.iterdir() if path.is_file())
            for path in files:
                if path.suffix in {".asc", ".md5", ".sha1", ".sha256", ".sha512"}:
                    continue
                for sidecar in (".asc", ".md5", ".sha1"):
                    if not path.with_name(path.name + sidecar).is_file():
                        raise SystemExit(f"Missing publication sidecar: {path}{sidecar}")
            paths.extend(files)

    destination.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(destination, "w", compression=ZIP_DEFLATED) as archive:
        for path in paths:
            archive.write(path, path.relative_to(staging).as_posix())
    print(f"Created {destination} with {len(paths)} files.")


if __name__ == "__main__":
    staging_dir, release_version, bundle = sys.argv[1:]
    build_bundle(Path(staging_dir), release_version, Path(bundle))
