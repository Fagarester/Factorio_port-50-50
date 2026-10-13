#!/usr/bin/env bash
# Применяет серию патчей к дереву Mesa ПО ОДНОМУ и никогда не оставляет полупримененный патч:
# патч берётся только если ВСЕ его куски ложатся (проверка --dry-run), иначе он пропускается и это видно в отчёте.
#   apply_series.sh <mesa-dir> <patches-dir> <variant: a8xx|standard>
# Отчёт: $REPORT (по умолчанию /tmp/patch-report.txt): строки APPLIED / SKIPPED <файл>.
set -u
MESA="${1:?mesa dir}"; P="${2:?patches dir}"; VARIANT="${3:?variant}"
REPORT="${REPORT:-/tmp/patch-report.txt}"
: > "$REPORT"
cd "$MESA"

try_patch() {   # <patch> <fuzz>
    local name; name="$(basename "$1")"
    if patch -p1 -N --fuzz="$2" --dry-run --no-backup-if-mismatch < "$1" > /tmp/dry.log 2>&1; then
        patch -p1 -N --fuzz="$2" --no-backup-if-mismatch < "$1" > /tmp/real.log 2>&1 \
            && { echo "APPLIED $name" >> "$REPORT"; echo "  applied  $name"; return 0; }
        echo "::error::$name passed the dry run but failed for real"; cat /tmp/real.log; exit 1
    fi
    echo "SKIPPED $name" >> "$REPORT"
    echo "  skipped  $name (does not apply to this Mesa commit):"
    grep -E "FAILED|can't find file|Reversed|malformed" /tmp/dry.log | head -n 8 | sed 's/^/             /'
    return 1
}

echo "== KGSL patches for glibc =="
if [ "${LINUX_PATCHES:-true}" = "true" ]; then
    for p in "$P"/linux/*.patch; do try_patch "$p" 3 || true; done
fi

# older Mesa (before MR 44838, 2026-10-08): the KGSL timestamp wait asserts on an unexpected errno -> make it a warning
python3 - <<'PY'
p = 'src/freedreno/vulkan/tu_knl_kgsl.cc'
s = open(p).read()
old = """      } else if (ret == -1) {
         assert(errno == ETIMEDOUT);
         return VK_TIMEOUT;"""
new = """      } else if (ret == -1) {
         if (errno != ETIMEDOUT)
            mesa_logw("wait_timestamp_safe: errno %d (%s)", errno, strerror(errno));
         return VK_TIMEOUT;"""
if old in s:
    open(p, 'w').write(s.replace(old, new, 1)); print('  kgsl wait: assert -> warning')
elif 'kgsl_timestamp_error(' in s:
    print('  kgsl wait: upstream already handles it')
else:
    print('::warning::kgsl wait: neither the assert nor upstream code found, check wait_timestamp_safe()')
PY

if [ "$VARIANT" = "a8xx" ]; then
    echo "== Max's winnative series (stability: 0003-0005; mesh shaders/wave32: 0001-0002) =="
    for p in "$P"/a8xx-winnative/0*.patch; do try_patch "$p" 3 || true; done

    echo "== whitebelyash gen8 stack =="
    try_patch "$P/a8xx_gen8.patch" 4 || true

    for s in a8xx_shared_mem.py a840v2.py; do
        before="$(git diff | sha256sum)"
        echo "== script $s =="
        python3 "$P/$s" || echo "::warning::$s failed"
        [ "$(git diff | sha256sum)" != "$before" ] && echo "APPLIED $s" >> "$REPORT" || echo "SKIPPED $s" >> "$REPORT"
    done
fi

# These fixes are in Mesa main since 2026-10-08 (MR 44838). Older commits lack them: only a warning, the driver still builds.
grep -q "kgsl_syncobj_merge(struct tu_device \*device" src/freedreno/vulkan/tu_knl_kgsl.cc \
    || echo "::warning::this Mesa lacks the upstream kgsl_syncobj_merge fix (MR 44838): submits waiting on two kinds of sync can crash"
grep -q "return kgsl_poll_timestamp(device, context_id, timestamp);" src/freedreno/vulkan/tu_knl_kgsl.cc \
    || echo "::warning::this Mesa lacks the upstream zero-timeout poll fix (MR 44838)"

python3 -c "compile(open('src/freedreno/common/freedreno_devices.py').read(),'f','exec')" \
    || { echo "::error::freedreno_devices.py does not parse after patching"; exit 1; }
[ -z "$(git status --porcelain --untracked-files=all | grep -E '\.(rej|orig)$' || true)" ] \
    || { echo "::error::patch leftovers (.rej/.orig) in the tree"; exit 1; }

echo; echo "== patch report =="; cat "$REPORT"
