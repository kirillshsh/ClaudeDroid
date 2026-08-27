# ClaudeDroid

## Пуш после правок

Любая правка кода заканчивается коммитом и пушем в `main` — не оставлять работу лежать локально.

Это верно и когда работаешь в worktree: временная ветка worktree никуда не пушится, коммиты доставляются в `main`.

Из своей worktree:

```bash
git fetch origin
```

```bash
git rebase origin/main
```

```bash
git push origin HEAD:main
```
