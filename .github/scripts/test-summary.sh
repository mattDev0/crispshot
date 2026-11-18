#!/usr/bin/env bash
# Prints a Markdown table of JUnit results from the XML files in the given directory.
set -euo pipefail
dir="${1:?usage: test-summary.sh <junit-xml-dir>}"

echo "### Unit tests"
if ! compgen -G "$dir/*.xml" > /dev/null; then
  echo "No test results found."
  exit 0
fi

echo "| Suite | Tests | Failures | Errors | Skipped |"
echo "|---|---:|---:|---:|---:|"
total=0; failed=0
for f in "$dir"/*.xml; do
  line=$(grep -m1 -o '<testsuite [^>]*>' "$f")
  attr() { sed -nE "s/.* $1=\"([^\"]*)\".*/\1/p" <<< "$line"; }
  name=$(attr name); tests=$(attr tests); failures=$(attr failures); errors=$(attr errors); skipped=$(attr skipped)
  echo "| ${name##*.} | $tests | $failures | $errors | $skipped |"
  total=$((total + tests)); failed=$((failed + failures + errors))
done
echo
echo "**$total tests, $failed failed.**"
