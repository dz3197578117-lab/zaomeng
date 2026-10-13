#!/usr/bin/env bash
#
# 环境探测的共用实现：JDK / Android SDK / aapt2 / Room3 KSP 的 sqlite。
#
# 由 scripts/build-android-apk.sh、check-env.sh、check-release-ready.sh 共同 source。
# 只在这里留一份，避免每个脚本各写一套探测逻辑、然后互相漂移。
#
# 约定：
#   - 所有函数在 set -euo pipefail 下安全；
#   - 需要「返回值」的函数把结果 echo 到 stdout，失败时 return 非零；
#   - prepare_aapt2_override 会把结果写进全局 AAPT2_OVERRIDE_ARG / AAPT2_OVERRIDE_NOTE，
#     并在调用方 shell 注册 EXIT trap 清理临时 wrapper 目录。

# ---------------------------------------------------------------------------
# JDK
# ---------------------------------------------------------------------------

# 实测 java -version 的 major；跑不起来或解析不出返回非零。
jdk_major() {
  local line
  [ -x "$1/bin/java" ] || return 1
  line="$("$1/bin/java" -version 2>&1 | head -n1)" || return 1
  printf '%s' "${line}" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n1
}

# 该目录是否为可用 JDK（能跑起来且主版本 >= 17）。
jdk_usable() {
  local major
  major="$(jdk_major "$1")" || return 1
  [ -n "${major}" ] || return 1
  [ "${major}" -ge 17 ] 2>/dev/null || return 1
  return 0
}

# 探测 JAVA_HOME。已显式设置则直接尊重。
# 注意：只检查 bin/java 是否存在并不够——机器上可能装着运行时坏掉的 JDK，
# 所以这里会实测。名字里带 21/17 的优先，其次取第一个能跑的。
detect_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    printf '%s' "${JAVA_HOME}"; return 0
  fi

  local candidates=() d
  for d in /usr/lib/jvm/* /opt/java/* /opt/*jdk* /opt/*-openjdk* \
           "${HOME}"/.sdkman/candidates/java/*; do
    [ -x "${d}/bin/java" ] && candidates+=("${d}")
  done

  local want
  for want in 21 17; do
    for d in "${candidates[@]:-}"; do
      [ -n "${d}" ] || continue
      case "$(basename "${d}")" in
        *"${want}"*)
          if jdk_usable "${d}"; then printf '%s' "${d}"; return 0; fi
          ;;
      esac
    done
  done

  for d in "${candidates[@]:-}"; do
    [ -n "${d}" ] || continue
    if jdk_usable "${d}"; then printf '%s' "${d}"; return 0; fi
  done

  # 都跑不起来时仍返回第一个候选，让构建自己报出更具体的错误
  if [ "${#candidates[@]}" -gt 0 ]; then
    printf '%s' "${candidates[0]}"; return 0
  fi

  if command -v java >/dev/null 2>&1; then
    local bin
    bin="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
    printf '%s' "$(dirname "$(dirname "${bin}")")"; return 0
  fi

  return 1
}

# ---------------------------------------------------------------------------
# Android SDK
# ---------------------------------------------------------------------------

detect_android_sdk() {
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "${HOME}/Android/Sdk" "${HOME}/Library/Android/sdk" \
           /opt/android-sdk /usr/lib/android-sdk /usr/local/lib/android/sdk; do
    [ -n "${d}" ] && [ -d "${d}" ] && { printf '%s' "${d}"; return 0; }
  done
  return 1
}

# 当前 compileSdk 对应的 android.jar（取版本号最大的 platform）。
find_android_jar() {
  local dir
  dir="$(ls -d "${ANDROID_HOME}"/platforms/android-* 2>/dev/null | sort -V | tail -n1 || true)"
  [ -n "${dir}" ] && [ -f "${dir}/android.jar" ] || return 1
  printf '%s' "${dir}/android.jar"
}

# ---------------------------------------------------------------------------
# aapt2
# ---------------------------------------------------------------------------
# arm64 主机上 AGP 从 Maven 取的 aapt2 是 x86-64 ELF，无法执行，必须覆盖。
# 但系统里的 aapt2 未必够新（Debian 打包的 2.19 读不了 compileSdk 37 的
# android.jar），所以不猜路径，而是**逐个实测**能否解析当前平台的 android.jar。

# Termux / Android 前缀下的 aapt2 需要 LD_LIBRARY_PATH 才能加载随包的 .so
ld_path_for() {
  case "$1" in
    */data/data/com.termux/files/usr/bin/*)
      printf '%s/lib' "$(dirname "$(dirname "$1")")" ;;
    *) return 1 ;;
  esac
}

_aapt2_candidates() {
  [ -n "${AAPT2_OVERRIDE:-}" ] && printf '%s\n' "${AAPT2_OVERRIDE}"
  command -v aapt2 >/dev/null 2>&1 && printf '%s\n' "$(command -v aapt2)"
  local p
  for p in "${PREFIX:-}/bin/aapt2" \
           /data/data/com.termux/files/usr/bin/aapt2 \
           /opt/termux/data/data/com.termux/files/usr/bin/aapt2 \
           /usr/bin/aapt2; do
    [ -n "${p}" ] && [ -x "${p}" ] && printf '%s\n' "${p}"
  done
  return 0
}

# 把 LD_LIBRARY_PATH 限制在 aapt2 进程内的 wrapper。
# 两个必须遵守的点：
#   1) 目录与 trap 必须在**父 shell** 建立。放进命令替换的子 shell，trap 会在
#      子 shell 退出时把刚写好的 wrapper 删掉，探测必然失败。
#   2) 文件名必须叫 aapt2：AGP 校验 override 文件名，否则报
#      "Custom AAPT2 location does not point to an AAPT2 executable"。
# 另外绝不能把 LD_LIBRARY_PATH 导出给整个构建：Android 的 bionic libc 会被 JVM
# 捡走，报 "libc.so: invalid ELF header"，构建还没开始就挂。
aapt2_wrapper() {
  local real="$1" ld="$2" name="${AAPT2_WRAPPER_DIR}/aapt2"
  {
    printf '#!/bin/sh\n'
    printf "LD_LIBRARY_PATH='%s'\nexport LD_LIBRARY_PATH\n" "${ld}"
    printf "exec '%s' \"\$@\"\n" "${real}"
  } > "${name}"
  chmod +x "${name}"
  printf '%s' "${name}"
}

# 结果写入全局 AAPT2_OVERRIDE_ARG / AAPT2_OVERRIDE_NOTE。
prepare_aapt2_override() {
  AAPT2_OVERRIDE_ARG=""
  AAPT2_OVERRIDE_NOTE=""
  AAPT2_WRAPPER_DIR=""

  # Windows（Git Bash / MSYS / Cygwin）宿主直接跳过这个覆盖。
  # 它是为 **arm64 宿主** 准备的：那类机器上 AGP 从 Maven 取到的 aapt2 是 x86-64 ELF，跑不了，
  # 必须换成能用的那份。而 Windows 宿主上 AGP 自带的 aapt2 架构本来就是对的，反倒会因为
  # override 的实现方式失败：为满足 AGP 的文件名校验，wrapper 是一个**名为 aapt2 的 POSIX
  # 脚本**（见下面 aapt2_wrapper 的注释），而 AGP 在 Windows 上无法把非 .exe 当可执行文件，
  # 于是报 "Custom AAPT2 location does not point to an AAPT2 executable"（本机实测）。
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
      AAPT2_OVERRIDE_NOTE="Windows 宿主：AGP 自带的 aapt2 架构正确，跳过覆盖（override 的 wrapper 是 POSIX 脚本，AGP 校验不过）"
      return 0
      ;;
  esac

  local jar
  if ! jar="$(find_android_jar)"; then
    AAPT2_OVERRIDE_NOTE="未找到 platforms/android-*/android.jar（SDK 里可能还没装 platform），跳过 aapt2 实测"
    return 0
  fi

  AAPT2_WRAPPER_DIR="$(mktemp -d)"
  trap 'rm -rf "${AAPT2_WRAPPER_DIR}"' EXIT

  local candidate target _ld
  while IFS= read -r candidate; do
    [ -n "${candidate}" ] && [ -x "${candidate}" ] || continue
    target="${candidate}"
    _ld=""
    if _ld="$(ld_path_for "${candidate}")"; then
      target="$(aapt2_wrapper "${candidate}" "${_ld}")"
    fi
    if "${target}" dump resources "${jar}" >/dev/null 2>&1; then
      AAPT2_OVERRIDE_ARG="-Pandroid.aapt2FromMavenOverride=${target}"
      AAPT2_OVERRIDE_NOTE="${candidate}${_ld:+  (wrapper 内设 LD_LIBRARY_PATH=${_ld})}"
      return 0
    fi
  done < <(_aapt2_candidates | awk '!seen[$0]++')

  AAPT2_OVERRIDE_NOTE="未找到能解析当前平台 android.jar 的 aapt2；若构建在资源链接阶段失败，请用 AAPT2_OVERRIDE 指定"
  return 0
}

# ---------------------------------------------------------------------------
# Room3 KSP 的 sqlite 原生库
# ---------------------------------------------------------------------------
# Room3 的 KSP 处理器默认挑 jar 内 Android/bionic 版 sqlite 原生库；
# 在「Android 设备上跑 glibc JVM」（proot / Termux）时会加载失败。
detect_ksp_sqlite() {
  local d
  for d in "${KSP_SQLITE_DIR:-}" /root/.dsh-ksp-libs; do
    [ -n "${d}" ] && [ -f "${d}/libsqlitejdbc.so" ] && { printf '%s' "${d}"; return 0; }
  done
  return 1
}

# 是否处于「Android 设备上跑 glibc JVM」的场景
is_android_host() {
  [ -f /system/build.prop ]
}

# 需要时把 sqlite 覆盖写进 JAVA_TOOL_OPTIONS（全局导出，JVM 会读）
apply_ksp_sqlite_override() {
  is_android_host || return 1
  local dir
  dir="$(detect_ksp_sqlite)" || return 1
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dorg.sqlite.lib.path=${dir} -Dorg.sqlite.lib.name=libsqlitejdbc.so"
  printf '%s' "${dir}"
  return 0
}

# ---------------------------------------------------------------------------
# 仓库辅助：签名指纹
# ---------------------------------------------------------------------------
# 这几段被 check-env.sh 与 check-release-ready.sh 共用，放这里避免两处解析漂移。

# release.yml 里配置的期望发布指纹（小写无冒号）；取不到时输出空。
expected_release_fingerprint() {
  sed -n 's/^[[:space:]]*EXPECTED_SIGNER_SHA256:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" \
    | head -n1
}

# 读 kmp/keystore.properties 的某个键值
keystore_property() {
  [ -f "$1" ] || return 1
  sed -n "s/^$2=//p" "$1" | head -n1
}

# 用 keytool 取密钥库证书指纹（小写无冒号）。强制英文 locale，否则
# 不同语言环境下 "SHA256:" 这个标签本身会变，导致解析失败。
keystore_fingerprint() {
  local ks="$1" pass="$2" alias="$3"
  [ -n "${ks}" ] && [ -f "${ks}" ] || return 1
  keytool -J-Duser.language=en -list -v -keystore "${ks}" \
    -storepass "${pass}" -alias "${alias}" 2>/dev/null \
    | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-Z' 'a-z' | head -n1
}

# 从 APK 取签名指纹（小写无冒号）。同样不能锚定行首：
# build-tools 37 输出 "V2 Signer: certificate SHA-256 digest:"，
# 36 及更早输出 "Signer #1 certificate SHA-256 digest:"。
apk_signer_fingerprint() {
  local apksigner="$1" apk="$2"
  "${apksigner}" verify --print-certs "${apk}" 2>/dev/null \
    | tr -d '\r' | tr 'A-Z' 'a-z' \
    | sed -n 's/.*sha-256 digest:[[:space:]]*//p' \
    | tr -d ':' | tr -d '[:space:]' | head -n1
}

# 应用版本号唯一来源 kmp/gradle.properties
app_version_name() {
  sed -n 's/^zaomeng\.versionName=//p' "$1" | head -n1
}

app_version_code() {
  sed -n 's/^zaomeng\.versionCode=//p' "$1" | head -n1
}
