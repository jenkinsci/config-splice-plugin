#!/usr/bin/env bash
#
# Runs the full test suite and collects decision-gate evidence on Linux.
#
# Written for AlmaLinux 9.x but has nothing distribution-specific beyond the package hints below.
# The point of running it is to close the Linux half of gates 3 and 4, which were measured on Windows
# only. See docs/adr/ADR-002 and ADR-004 for what is expected to differ.
#
#   ./scripts/run-gates-linux.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${REPO_ROOT}/.tools"
MAVEN_VERSION="3.9.16"
MAVEN_HOME="${TOOLS_DIR}/apache-maven-${MAVEN_VERSION}"

log()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[33mWARNING: %s\033[0m\n' "$*" >&2; }
fail() { printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Running as root is allowed but warned about.
#
# Maven as root leaves a root-owned target/ and /root/.m2, which breaks later non-root builds in the
# same checkout. On a dedicated or disposable test box that costs nothing, so this warns rather than
# refuses; blocking a workflow that is perfectly reasonable there would be obstructive.
# Set CONFIG_SPLICE_ALLOW_ROOT=1 to silence.
# ---------------------------------------------------------------------------
if [[ "${EUID}" -eq 0 && "${CONFIG_SPLICE_ALLOW_ROOT:-0}" != "1" ]]; then
  warn "Running as root. target/ and /root/.m2 will be root-owned, which will break later
         non-root builds in this checkout. Fine on a dedicated test box; on a shared machine
         prefer a normal user. Set CONFIG_SPLICE_ALLOW_ROOT=1 to silence this."
fi

# ---------------------------------------------------------------------------
# Jansi needs an executable temp directory for its native library. Many hardened hosts mount /tmp
# with noexec, which produces an alarming UnsatisfiedLinkError that has nothing to do with the build
# and only affects log colouring. Point it somewhere writable and executable instead.
# ---------------------------------------------------------------------------
JANSI_TMP="${TOOLS_DIR}/jansi-tmp"
mkdir -p "${JANSI_TMP}"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djansi.tmpdir=${JANSI_TMP}"

# ---------------------------------------------------------------------------
# Java 17
#
# The build targets Java 17 because Jenkins 2.541.x is the newest LTS line that still supports it
# (2.555+ requires Java 21). Building on a newer JDK is fine; the release target stays 17.
# ---------------------------------------------------------------------------
log "Checking Java"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17 /usr/lib/jvm/jre-17; do
    [[ -d "${candidate}" ]] && export JAVA_HOME="${candidate}" && break
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  fail "No JDK 17 found. On AlmaLinux 9:  sudo dnf install -y java-17-openjdk-devel
Then re-run, or set JAVA_HOME explicitly."
fi
"${JAVA_HOME}/bin/java" -version
echo "JAVA_HOME=${JAVA_HOME}"

# ---------------------------------------------------------------------------
# Maven 3.9.6+
#
# AlmaLinux 9 AppStream ships Maven 3.8.x, which is too old: the Jenkins plugin build fails with
# "Unknown packaging: hpi". So a suitable Maven is fetched into .tools/ rather than installed.
# ---------------------------------------------------------------------------
log "Checking Maven"
MVN=""
if command -v mvn >/dev/null 2>&1; then
  system_version="$(mvn -v 2>/dev/null | sed -n 's/^Apache Maven \([0-9.]*\).*/\1/p')"
  major="${system_version%%.*}"
  minor="$(echo "${system_version}" | cut -d. -f2)"
  if [[ "${major}" -gt 3 || ( "${major}" -eq 3 && "${minor}" -ge 9 ) ]]; then
    MVN="$(command -v mvn)"
    echo "Using system Maven ${system_version}"
  else
    echo "System Maven ${system_version} is too old (need 3.9.6+); fetching a local copy."
  fi
fi

if [[ -z "${MVN}" ]]; then
  if [[ ! -x "${MAVEN_HOME}/bin/mvn" ]]; then
    mkdir -p "${TOOLS_DIR}"
    url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    echo "Downloading ${url}"
    curl -fsSL "${url}" -o "${TOOLS_DIR}/maven.tar.gz" || fail "Could not download Maven. Check network/proxy."
    tar -xzf "${TOOLS_DIR}/maven.tar.gz" -C "${TOOLS_DIR}"
    rm -f "${TOOLS_DIR}/maven.tar.gz"
  fi
  MVN="${MAVEN_HOME}/bin/mvn"
fi
"${MVN}" -v | head -1

# ---------------------------------------------------------------------------
# Build
#
# The first run downloads the Jenkins parent POM, the plugin BOM and the test harness (including a
# Jenkins war for the gate 2 agent test), so it needs network access and takes a few minutes.
# ---------------------------------------------------------------------------
log "Running the test suite"
cd "${REPO_ROOT}"
set +e
"${MVN}" -B -ntp test
build_status=$?
set -e

# ---------------------------------------------------------------------------
# Evidence
# ---------------------------------------------------------------------------
log "Decision-gate evidence"
evidence_dir="${REPO_ROOT}/target/gate-evidence"
if [[ -d "${evidence_dir}" ]]; then
  for file in "${evidence_dir}"/*.txt; do
    [[ -e "${file}" ]] || continue
    echo
    cat "${file}"
  done
  echo
  echo "Evidence files are in ${evidence_dir}"
  echo "Compare these against the Windows results quoted in docs/adr/ADR-002 and ADR-004."
else
  echo "No evidence produced - the build likely failed before the gate tests ran."
fi

log "What to look for on Linux"
cat <<'EXPECTED'
  Gate 3 (workspace confinement)
    - symbolic link probes should now be CONCLUSIVE (no elevation needed on Linux)
    - junction probes should report "not applicable on this platform"
    - every escape attempt must still be refused with WORKSPACE_ESCAPE

  Gate 4 (atomic replacement)
    - "POSIX permission preservation" should report VERIFIED (mode rw-r----- survives)
    - "permissions explicitly transferred" should now say yes
    - open-handle probes are expected to SUCCEED rather than be blocked: POSIX rename() over an
      open file works, leaving existing readers on the old inode. If so, the retry added for
      Windows is inert here and costs nothing. Confirm rather than assume.
EXPECTED

exit "${build_status}"
