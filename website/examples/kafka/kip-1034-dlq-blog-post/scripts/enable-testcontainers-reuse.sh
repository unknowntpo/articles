#!/usr/bin/env bash
set -euo pipefail

config_file="${HOME}/.testcontainers.properties"
line='testcontainers.reuse.enable=true'

mkdir -p "$(dirname "$config_file")"
touch "$config_file"

if grep -Fxq "$line" "$config_file"; then
  echo "Already enabled in $config_file"
  exit 0
fi

printf '\n%s\n' "$line" >> "$config_file"
echo "Enabled Testcontainers reuse in $config_file"
