# ECC for Codex CLI

This file is the repo-local ECC baseline for Codex CLI sessions in this repository (there is no root `AGENTS.md`; this file stands alone).

## Repo Skill

- Repo-generated Codex skill: `.agents/skills/music-visualizer-2/SKILL.md`
- Claude-facing companion skill: `.claude/skills/music-visualizer-2/SKILL.md`
- Keep user-specific credentials and private MCPs in `~/.codex/config.toml`, not in this repo.

## MCP Baseline

Treat `.codex/config.toml` as the default ECC-safe baseline for work in this repository.
The generated baseline enables GitHub, Context7, Exa, Memory, Playwright, and Sequential Thinking.

## Multi-Agent Support

- Explorer: read-only evidence gathering
- Reviewer: correctness, security, and regression review
- Docs researcher: API and release-note verification

## Workflow Files

- No dedicated workflow command files were generated for this repo.

Use these workflow files as reusable task scaffolds when the detected repository workflows recur.