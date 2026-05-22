---
name: commit
description: Stage and commit changes using the project's commit convention. Use when the user says "commit this", "commit changes", or asks to create a git commit.
argument-hint: [optional commit message]
---

Create a git commit following the project's commit convention.

## Steps

1. Run `git status` and `git diff` in parallel to understand what changed.
2. Run `git log --oneline -5` to match the existing commit message style.
3. Determine which files to stage:
   - Stage only files relevant to the current task.
   - Never stage `.env`, credentials, or secrets.
   - Never stage `.claude/settings.local.json`.
   - Prefer staging specific files by name over `git add -A` or `git add .`.
4. Draft a commit message following the project convention:
   ```
   feat: 새 기능
   fix: 버그 수정
   refactor: 리팩토링
   docs: 문서
   test: 테스트
   chore: 빌드, 의존성
   ```
   - If `$ARGUMENTS` is provided, use it as the commit message directly.
   - Otherwise, infer the type and write a concise Korean or English message matching the repo style.
5. Stage the files and create the commit. Always pass the message via HEREDOC:
   ```bash
   git commit -m "$(cat <<'EOF'
   type: message

   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
   EOF
   )"
   ```
6. Run `git status` to confirm success.
7. Report the commit hash and message to the user.

## Rules
- NEVER amend existing commits unless explicitly asked.
- NEVER use `--no-verify`.
- NEVER force push.
- Do NOT push to remote unless explicitly asked.
