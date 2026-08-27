#!/data/data/com.termux/files/usr/bin/bash
# Claude Droid — установка Claude Code в Termux, с запуском от root.
#
#   curl -sSL https://raw.githubusercontent.com/kirillshsh/ClaudeDroid/main/setup-termux.sh | bash
#
# Ставит: tmux + node, musl-загрузчик из Alpine, musl-сборку claude,
# обёртки claude/claude-update и Magisk-модуль с /etc/resolv.conf.
# Запускать можно повторно — всё идемпотентно.
set -e

P=${PREFIX:-/data/data/com.termux/files/usr}
H=${HOME:-/data/data/com.termux/files/home}
ALPINE=https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64
NEED_REBOOT=0

say()  { printf '\n\033[1;38;2;217;119;87m▶ %s\033[0m\n' "$1"; }
warn() { printf '\033[33m!  %s\033[0m\n' "$1"; }
die()  { printf '\n\033[1;31m✗  %s\033[0m\n' "$1" >&2; exit 1; }

[ "$(uname -m)" = aarch64 ] || die "нужен 64-битный ARM, а тут $(uname -m)"
[ -d "$P/bin" ] || die "это не Termux: нет $P/bin"

# su у Magisk лежит не в PATH приложения — ищем по известным местам
SU=
for c in /system/bin/su /system_ext/bin/su /debug_ramdisk/su; do
  [ -x "$c" ] && SU=$c && break
done
[ -n "$SU" ] || die "не нашёл su — нужен root (Magisk)"
[ "$("$SU" -M -c id -u 2>/dev/null)" = 0 ] || die "Magisk не дал root Termux'у — разреши в приложении Magisk и запусти скрипт снова"

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

say "Пакеты Termux"
pkg install -y tmux nodejs-lts git

say "musl-загрузчик из Alpine"
if [ -f "$P/lib/musl/ld-musl-aarch64.so.1" ]; then
  echo "уже есть"
else
  MR=$(curl -sSL "$ALPINE/latest-releases.yaml" | grep -o 'alpine-minirootfs-[0-9.]*-aarch64\.tar\.gz' | head -1)
  [ -n "$MR" ] || die "не нашёл alpine-minirootfs на dl-cdn.alpinelinux.org"
  echo "$MR"
  curl -# -L -o "$T/mr.tgz" "$ALPINE/$MR"
  # распаковываем весь rootfs (3 МБ, одни файлы и симлинки) и ищем загрузчик:
  # путь внутри архива между версиями Alpine меняется — то ./lib, то lib
  mkdir -p "$T/mr"
  tar xzf "$T/mr.tgz" -C "$T/mr"
  LD=$(find "$T/mr" -name ld-musl-aarch64.so.1 -type f | head -1)
  [ -n "$LD" ] || die "в $MR нет ld-musl-aarch64.so.1"
  mkdir -p "$P/lib/musl"
  install -m755 "$LD" "$P/lib/musl/ld-musl-aarch64.so.1"
  ln -sf ld-musl-aarch64.so.1 "$P/lib/musl/libc.musl-aarch64.so.1"
fi

say "Claude Code (musl-сборка)"
mkdir -p "$P/lib/claude-code"
V=$(npm view @anthropic-ai/claude-code version)
echo "$V"
TARBALL=$(npm view "@anthropic-ai/claude-code-linux-arm64-musl@$V" dist.tarball)
[ -n "$TARBALL" ] || die "npm не отдал тарбол claude-code-linux-arm64-musl@$V"
curl -# -L -o "$T/cc.tgz" "$TARBALL"
tar xzf "$T/cc.tgz" -C "$T" package/claude
install -m755 "$T/package/claude" "$P/lib/claude-code/claude"

say "Обёртки claude и claude-update"
cat > "$P/bin/claude" <<EOF
#!$P/bin/bash
# Claude Code для Termux: musl-сборка через musl-загрузчик, запуск от root (полный доступ к телефону).
# CLAUDE_NO_ROOT=1 — запустить без root, от uid Termux.
P=$P
if [ "\$(id -u)" -ne 0 ] && [ "\$CLAUDE_NO_ROOT" != 1 ]; then
  a=\$(printf '%q ' "\$@")
  exec $SU -M -c "env HOME=\$HOME PREFIX=\$P PATH=\$P/bin:/system/bin:/system/xbin TMPDIR=\$P/tmp LANG=\${LANG:-en_US.UTF-8} TERM=\${TERM:-xterm-256color} SHELL=\$P/bin/bash IS_SANDBOX=1 COLORTERM=\${COLORTERM:-truecolor} \$P/bin/claude \$a"
fi
export IS_SANDBOX=1
exec "\$P/lib/musl/ld-musl-aarch64.so.1" --library-path "\$P/lib/musl" "\$P/lib/claude-code/claude" "\$@"
EOF

cat > "$P/bin/claude-update" <<EOF
#!$P/bin/bash
# Обновить Claude Code (musl-сборка с npm)
set -e
P=$P
V=\${1:-\$(npm view @anthropic-ai/claude-code version)}
echo "→ \$V"
T=\$(mktemp -d); cd "\$T"
curl -sSL -o m.tgz "\$(npm view @anthropic-ai/claude-code-linux-arm64-musl@\$V dist.tarball)"
tar xzf m.tgz
install -m755 package/claude "\$P/lib/claude-code/claude.new"
mv -f "\$P/lib/claude-code/claude.new" "\$P/lib/claude-code/claude"
cd /; rm -rf "\$T"
claude --version
EOF
chmod 755 "$P/bin/claude" "$P/bin/claude-update"

say "/etc/resolv.conf (Magisk-модуль)"
# musl резолвит DNS через /etc/resolv.conf, а в Android его нет: bionic ходит в netd.
# Без файла claude молча виснет на первом же запросе.
if [ -r /system/etc/resolv.conf ]; then
  echo "уже есть"
else
  mkdir -p "$T/mod/system/etc"
  cat > "$T/mod/module.prop" <<'EOF'
id=etc-resolv
name=/etc/resolv.conf for musl binaries
version=v1.0
versionCode=1
author=Claude Droid
description=Добавляет /system/etc/resolv.conf. Нужен musl-программам (Claude Code в Termux): bionic резолвит через netd, musl читает /etc/resolv.conf, которого в Android нет.
EOF
  cat > "$T/mod/system/etc/resolv.conf" <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
options timeout:2 attempts:2
EOF
  "$SU" -M -c "rm -rf /data/adb/modules/etc-resolv && cp -r $T/mod /data/adb/modules/etc-resolv && chown -R 0:0 /data/adb/modules/etc-resolv && chmod -R 755 /data/adb/modules/etc-resolv"
  NEED_REBOOT=1
  echo "модуль установлен"
fi

say "Настройки Claude Code"
# приложение снимает экран tmux построчно — нужен обычный CLI-рендерер, не fullscreen TUI
mkdir -p "$H/.claude"
[ -f "$H/.claude/settings.json" ] || echo '{}' > "$H/.claude/settings.json"
node -e '
const fs = require("fs"), f = process.argv[1];
let s = {};
try { s = JSON.parse(fs.readFileSync(f, "utf8")); } catch (e) { console.error("settings.json битый, перезаписываю"); }
s.tui = "default";
fs.writeFileSync(f, JSON.stringify(s, null, 2) + "\n");
' "$H/.claude/settings.json"
echo '"tui": "default"'

say "Готово"
"$P/bin/claude" --version || warn "claude не запустился — если это таймаут, скорее всего нужен ребут (см. ниже)"

echo
if [ "$NEED_REBOOT" = 1 ]; then
  printf '\033[1m1.\033[0m Перезагрузи телефон — без этого Magisk-модуль с resolv.conf не смонтирован и claude виснет на DNS.\n'
  printf '\033[1m2.\033[0m Запусти \033[1mclaude\033[0m и войди в аккаунт: \033[1m/login\033[0m\n'
  printf '\033[1m3.\033[0m Поставь APK Claude Droid и выдай ему root в Magisk.\n'
else
  printf '\033[1m1.\033[0m Запусти \033[1mclaude\033[0m и войди в аккаунт: \033[1m/login\033[0m\n'
  printf '\033[1m2.\033[0m Поставь APK Claude Droid и выдай ему root в Magisk.\n'
fi
echo
