#!/bin/bash
# Runs every scenario one after the other (they share local ports 8088 and 9000). ~25 minutes.
#   bash run-all.sh            everything
#   bash run-all.sh call lock  just those scenarios        (suffix :LOCK=1 runs one with the end-to-end lock on, e.g. call:LOCK=1)
cd "$(dirname "$0")"
LIST=("$@"); [ ${#LIST[@]} -eq 0 ] && LIST=(static pwa call join watch relay slowanswer qol rotate rotatewatch zoom diag unavail noroute blackhole fx lock admit settings extras contrast layout call:LOCK=1 join:LOCK=1 watch:LOCK=1 relay:LOCK=1 slowanswer:LOCK=1)
for u in api e2ee-basic e2ee perf; do node $u.js | tail -1 | sed "s/^/[$u] /"; done
FAIL=0
for sc in "${LIST[@]}"; do
  name=${sc%%:*}; envs=""; [[ "$sc" == *:* ]] && envs=${sc#*:}
  out=$(env $envs timeout 600 node suite.js "$name" 2>&1); echo "$out" | grep -E "FAIL|PAGEERROR|SCENARIO|HARNESS|LAYOUT" | sed "s/^/[$sc] /"
  echo "$out" | grep -q "-> PASS" || FAIL=1
  sleep 2
done
[ $FAIL -eq 0 ] && echo "ALL PASSED" || { echo "SOME FAILED"; exit 1; }
