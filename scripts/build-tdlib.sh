#!/bin/sh
# Build the pinned official TDLib Java/JNI bundle for the current macOS or Linux host.
# Windows keeps scripts/build-tdlib.ps1. Android is not a desktop JNI target.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
pin="$root/native/tdlib-source.properties"
commit=$(sed -n 's/^commit=//p' "$pin")
repository=$(sed -n 's/^repository=//p' "$pin")
os=$(uname -s)
arch=$(uname -m)
case "$os:$arch" in
  Darwin:arm64) bundle="$root/native/macos-arm64"; architecture=arm64; jni=libtdjni.dylib ;;
  Darwin:x86_64) bundle="$root/native/macos-x64"; architecture=x64; jni=libtdjni.dylib ;;
  Linux:x86_64) bundle="$root/native/linux-x64"; architecture=x64; jni=libtdjni.so ;;
  Linux:aarch64) bundle="$root/native/linux-arm64"; architecture=arm64; jni=libtdjni.so ;;
  *) echo "Unsupported desktop host: $os $arch" >&2; exit 1 ;;
esac
jdk=${JAVA_HOME:-}
if [ -z "$jdk" ] || [ ! -x "$jdk/bin/javac" ]; then
  echo "Set JAVA_HOME to a JDK 21 directory." >&2
  exit 1
fi
for tool in git cmake ninja g++ openssl python3 gperf; do
  command -v "$tool" >/dev/null || { echo "Missing toolchain command: $tool" >&2; exit 1; }
done
build="$root/build/native"
source="$build/td-source"
mkdir -p "$build" "$bundle"
if [ ! -d "$source/.git" ]; then
  git init "$source"
  git -C "$source" remote add origin "$repository"
  git -C "$source" fetch --depth 1 origin "$commit"
  git -C "$source" checkout --detach FETCH_HEAD
fi
test "$(git -C "$source" rev-parse HEAD)" = "$commit" || { echo "Existing TDLib checkout differs from the pin." >&2; exit 1; }
jni_build="$build/jni-build"
java_build="$build/java-build"
java_source="$source/example/java"
openssl_root=
if [ "$os" = Darwin ]; then openssl_root=$(brew --prefix openssl); fi
cmake -S "$source" -B "$jni_build" -G Ninja -DCMAKE_BUILD_TYPE=Release "-DOPENSSL_ROOT_DIR=$openssl_root" \
  -DTD_ENABLE_JNI=ON -DTD_ENABLE_LTO=OFF -DCMAKE_INSTALL_PREFIX="$java_source/td" -DJAVA_HOME="$jdk"
cmake --build "$jni_build" --target install --parallel "${JOBS:-4}"
# JNI uses the VM's function table. Drop its unused link to the build JDK's libjvm.
link_flags=
if [ "$os" = Darwin ]; then link_flags=-Wl,-dead_strip_dylibs; fi
cmake -S "$java_source" -B "$java_build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  "-DCMAKE_SHARED_LINKER_FLAGS=$link_flags" \
  -DTd_DIR="$java_source/td/lib/cmake/Td" -DCMAKE_INSTALL_PREFIX="$java_source" -DJAVA_HOME="$jdk"
cmake --build "$java_build" --target install --parallel "${JOBS:-4}"
staging="$build/java-staging"
classes="$staging/classes"
mkdir -p "$classes/META-INF"
python3 - "$java_source" "$staging" "$commit" <<'PY'
import pathlib, re, sys
java_source = pathlib.Path(sys.argv[1])
staging = pathlib.Path(sys.argv[2])
commit = sys.argv[3]
original = re.compile(r"(?s)    static \{\s*try \{\s*System\.loadLibrary\(\"tdjni\"\);\s*\} catch \(UnsatisfiedLinkError e\) \{\s*e\.printStackTrace\(\);\s*\}\s*\}")
replacement = '''    static {
        String path = System.getProperty("nnggram.tdlib.library");
        if (path == null || path.isEmpty()) {
            System.loadLibrary("tdjni");
        } else {
            System.load(path);
        }
    }'''
for name in ("Client.java", "TdApi.java"):
    text = (java_source / "org/drinkless/tdlib" / name).read_text()
    if name == "TdApi.java":
        found = re.search(r'GIT_COMMIT_HASH = "([a-f0-9]{40})"', text)
        if not found or found.group(1) != commit:
            raise SystemExit("Generated API does not match the pinned commit")
    if not original.search(text):
        raise SystemExit(f"{name} loader changed; review before rebuilding")
    (staging / name).write_text(original.sub(replacement, text, count=1))
PY
printf 'source.commit=%s\njava.api.commit=%s\nloader.patch=absolute-path-v1\n' "$commit" "$commit" > "$classes/META-INF/nnggram-tdlib.properties"
"$jdk/bin/javac" -encoding UTF-8 --release 21 -d "$classes" "$staging/Client.java" "$staging/TdApi.java"
"$jdk/bin/jar" --create --file "$bundle/tdlib.jar" -C "$classes" .
native_file=$(find "$java_source" "$jni_build" -name "$jni" -type f | head -n 1)
test -n "$native_file" || { echo "JNI build did not produce $jni" >&2; exit 1; }
cp "$native_file" "$bundle/$jni"
cp "$source/LICENSE_1_0.txt" "$bundle/TDLib-LICENSE.txt"
{
  printf 'format=1\nsource.commit=%s\narchitecture=%s\nbinding=official-java-jni\n' "$commit" "$architecture"
  printf 'binding.loader.patch=absolute-path-v1\nload.order=%s\ncompiler=%s\n' "$jni" "$(g++ -dumpfullversion 2>/dev/null || g++ -dumpversion)"
  for name in tdlib.jar "$jni"; do
    if command -v shasum >/dev/null; then hash=$(shasum -a 256 "$bundle/$name" | awk '{print $1}')
    else hash=$(sha256sum "$bundle/$name" | awk '{print $1}'); fi
    printf 'sha256.%s=%s\n' "$name" "$hash"
  done
} > "$bundle/manifest.properties"
if [ "$os" = Darwin ]; then
  python3 "$root/scripts/bundle-macos-libraries.py" "$bundle/$jni" --tdlib-manifest
  mkdir -p "$bundle/licenses/openssl"
  openssl_prefix=$(brew --prefix openssl 2>/dev/null || true)
  if [ -n "$openssl_prefix" ]; then cp "$openssl_prefix/LICENSE.txt" "$bundle/licenses/openssl/"; fi
fi
echo "Prepared pinned TDLib bundle at $bundle"
echo "Login still requires telegram-api.local.properties. Video sound, voice input and Windows toast stay on their original hosts."
