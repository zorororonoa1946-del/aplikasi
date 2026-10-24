#!/usr/bin/env bash
# This runs only synthetic fixtures on the disposable CI emulator.
set -euo pipefail
cd "$(dirname "$0")/.."
report="${RUNNER_TEMP:-/tmp}/nusa-android-ci.log"
if npm run test:android > "$report" 2>&1; then
  cat "$report"
else
  result=$?
  cat "$report"
  python3 - "$report" <<'PY'
import sys
from pathlib import Path
text=Path(sys.argv[1]).read_text(errors='replace')[-14000:]
# GitHub renders annotations on public run pages, even when detailed logs
# require a signed-in account. Only this synthetic test runner is captured.
text=text.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print('::error title=Android integration diagnostics::'+text)
PY
  exit "$result"
fi
