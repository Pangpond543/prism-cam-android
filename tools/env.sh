# source tools/env.sh — toolchain for Prism Cam Android (Git Bash). Everything lives outside AppData and OneDrive.
_U="$(cygpath -u "$USERPROFILE")"
_P="$_U/PrismAndroid"
export JAVA_HOME="$_P/jdk"
export ANDROID_HOME="$(cygpath -w "$_P/sdk")"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$_P/jdk/bin:$_P/gradle/bin:$_P/sdk/platform-tools:$PATH"
export PRISM_OUT="$_U/PrismCamAndroidBuild"
export PRISM_BUILD_DIR="$(cygpath -w "$PRISM_OUT")"
export KEYPROPS="$_U/PrismKeys/prism-cam-android.properties"
GRADLE_FLAGS=(--console=plain
  --project-cache-dir "$(cygpath -w "$PRISM_OUT/gradle-project")"
  "-Pkotlin.project.persistent.dir=$(cygpath -w "$PRISM_OUT/kotlin")")
