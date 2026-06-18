#!/usr/bin/env bash
set -euo pipefail

agent="${1:-react}"

case "$agent" in
  react)
    main_class="com.huawei.ascend.agentsdk.example.OpenJiuwenReactAgentSdkExample"
    ;;
  deepagent)
    main_class="com.huawei.ascend.agentsdk.example.OpenJiuwenDeepAgentSdkExample"
    ;;
  *)
    echo "Usage: $0 [react|deepagent]" >&2
    exit 2
    ;;
esac

export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-$LANG}"

append_java_tool_option() {
  local option="$1"
  local property="${option%%=*}"
  if [[ -z "${JAVA_TOOL_OPTIONS:-}" ]]; then
    export JAVA_TOOL_OPTIONS="$option"
  elif [[ "$JAVA_TOOL_OPTIONS" != *"$property"* ]]; then
    export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS $option"
  fi
}

append_java_tool_option "-Dfile.encoding=UTF-8"
append_java_tool_option "-Dsun.stdout.encoding=UTF-8"
append_java_tool_option "-Dsun.stderr.encoding=UTF-8"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"

mvn -f "$repo_root/examples/agent-sdk-example/pom.xml" compile exec:java \
  "-Dexample.mainClass=$main_class"
