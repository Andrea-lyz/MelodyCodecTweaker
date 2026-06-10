#!/usr/bin/env bash
set -euo pipefail

decoded_dir="${1:-}"
if [[ -z "${decoded_dir}" ]]; then
  echo "Usage: $0 <decoded-apk-dir>" >&2
  exit 2
fi

manifest="${decoded_dir}/AndroidManifest.xml"
report="${decoded_dir}/melody_patch_report.md"

if [[ ! -f "${manifest}" ]]; then
  echo "AndroidManifest.xml not found in ${decoded_dir}" >&2
  exit 1
fi

mkdir -p "${decoded_dir}/assets"

{
  echo "# Melody Patch Report"
  echo
  echo "Stage: repack-smoke"
  echo "Generated: $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  echo
  echo "## Manifest checks"
  if grep -q 'package="com.oplus.melody"' "${manifest}"; then
    echo "- package: com.oplus.melody"
  else
    echo "- package: unexpected"
    exit 1
  fi
  if grep -q 'com.oplus.melody.ui.component.detail.DetailMainActivity' "${manifest}"; then
    echo "- DetailMainActivity: present"
  else
    echo "- DetailMainActivity: missing"
  fi
  if grep -q 'com.oplus.melody.onespace.OneSpaceDetailActivity' "${manifest}"; then
    echo "- OneSpaceDetailActivity: present"
  else
    echo "- OneSpaceDetailActivity: missing"
  fi
  echo
  echo "## Patch status"
  echo "- No helper dex injected yet."
  echo "- No smali entrypoint patched yet."
  echo "- This artifact only verifies decode/rebuild/sign/install feasibility."
} > "${report}"

cat > "${decoded_dir}/assets/melody_patch_marker.txt" <<'EOF'
Melody non-root patch workspace marker.
Current stage: repack-smoke.
No codec control code has been injected yet.
EOF

echo "Patch smoke report written to ${report}"
