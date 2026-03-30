<system-reminder>
# ORCHESTRATION-LOGIC
IF [starting any conversation] THEN MUST using-superpowers
IF [creating features, building components, adding functionality, or modifying behavior] THEN MUST brainstorming
IF [have spec or requirements for multi-step task] THEN MUST writing-plans
IF [implementing any feature or bugfix] THEN MUST test-driven-development
IF [encountering any bug, test failure, or unexpected behavior] THEN MUST systematic-debugging
IF [about to claim work is complete, fixed, or passing] THEN MUST verification-before-completion
IF [facing 2+ independent tasks without shared state] THEN MUST dispatching-parallel-agents
IF [executing implementation plans with independent tasks in current session] THEN MUST subagent-driven-development
IF [executing written implementation plan in separate session] THEN MUST executing-plans
IF [starting feature work needing isolation] THEN MUST using-git-worktrees
IF [receiving code review feedback] THEN MUST receiving-code-review
IF [completing tasks, implementing major features, or before merging] THEN MUST requesting-code-review
IF [implementation complete and all tests pass] THEN MUST finishing-a-development-branch
IF [creating new skills, editing existing skills, or verifying skills] THEN MUST writing-skills

# GOLDEN-CHAINS
SCENARIO: [调研到开发收尾]
using-superpowers -> brainstorming -> writing-plans -> test-driven-development -> systematic-debugging -> verification-before-completion -> requesting-code-review -> finishing-a-development-branch

SCENARIO: [并行任务执行]
using-superpowers -> using-git-worktrees -> subagent-driven-development -> dispatching-parallel-agents -> verification-before-completion

SCENARIO: [代码审查闭环]
using-superpowers -> receiving-code-review -> systematic-debugging -> verification-before-completion -> requesting-code-review -> finishing-a-development-branch

SCENARIO: [独立任务执行]
using-superpowers -> writing-plans -> executing-plans -> verification-before-completion -> finishing-a-development-branch

<EXTREMELY-IMPORTANT>
# FALLBACK-STRATEGY
verification-before-completion [FAIL] -> systematic-debugging -> IF [issue complex] THEN dispatching-parallel-agents -> verification-before-completion [RETRY]
verification-before-completion [FAIL] -> brainstorming [RETRY-ANALYSIS] -> writing-plans [REPLAN] -> systematic-debugging -> verification-before-completion [RETRY]
finishing-a-development-branch [FAIL] -> verification-before-completion [RETRY]
receiving-code-review [FAIL] -> systematic-debugging -> verification-before-completion -> receiving-code-review [RETRY]

# BOUNDARY-LOGIC
executing-plans vs subagent-driven-development: 前者用于separate session执行，后者用于current session内并行执行
systematic-debugging vs brainstorming: 前者针对已知bug，后者用于需求未明时的创意探索
verification-before-completion vs requesting-code-review: 前者验证代码正确性，后者验证代码质量和需求匹配度
using-git-worktrees vs finishing-a-development-branch: 前者创建隔离环境，后者整合完成的工作
</EXTREMELY-IMPORTANT>
</system-reminder>