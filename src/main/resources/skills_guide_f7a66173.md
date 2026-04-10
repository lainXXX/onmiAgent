<system-reminder>
# ORCHESTRATION-LOGIC
IF 任何创意工作 THEN MUST brainstorming
IF 用户要求创建海报、艺术作品、设计或静态作品 THEN MUST canvas-design
IF 面临2+个独立任务且无共享状态或顺序依赖 THEN MUST dispatching-parallel-agents
IF 有书面实现计划需分会话执行含审查检查点 THEN MUST executing-plans
IF 实现完成、测试通过且需决定集成方式 THEN MUST finishing-a-development-branch
IF 用户要求构建web组件、页面、应用程序或任何web界面 THEN MUST frontend-design
IF 收到代码审查反馈且在实施建议之前 THEN MUST receiving-code-review
IF 完成任务、实现主要功能或合并之前 THEN MUST requesting-code-review
IF 在当前会话执行含独立任务的实现计划 THEN MUST subagent-driven-development
IF 遇到bug、测试失败或任何意外行为 THEN MUST systematic-debugging
IF 实现任何功能或bugfix THEN MUST test-driven-development
IF 开始需与当前工作区隔离的功能工作或执行实现计划 THEN MUST using-git-worktrees
IF 开始任何对话 THEN MUST using-superpowers
IF 即将声称工作完成、修复或通过之前 THEN MUST verification-before-completion
IF 有规范或需求用于多步骤任务且在接触代码之前 THEN MUST writing-plans
IF 创建新技能、编辑现有技能或验证技能部署 THEN MUST writing-skills

# GOLDEN-CHAINS
SCENARIO: brainstorming -> writing-plans -> test-driven-development -> frontend-design -> systematic-debugging -> verification-before-completion -> requesting-code-review -> finishing-a-development-branch

SCENARIO: brainstorming -> writing-plans -> dispatching-parallel-agents -> verification-before-completion -> receiving-code-review -> systematic-debugging -> verification-before-completion -> finishing-a-development-branch

SCENARIO: brainstorming -> canvas-design -> verification-before-completion -> finishing-a-development-branch

<EXTREMELY-IMPORTANT>
# FALLBACK-STRATEGY
verification-before-completion FAIL -> systematic-debugging
systematic-debugging FAIL -> receiving-code-review
receiving-code-review -> systematic-debugging -> verification-before-completion
verification-before-completion FAIL -> brainstorming
brainstorming -> writing-plans -> test-driven-development
verification-before-completion FAIL -> test-driven-development
test-driven-development -> systematic-debugging -> verification-before-completion

# BOUNDARY-LOGIC
brainstorming 专注意图探索和需求调研，writing-plans 专注规范制定和步骤规划，两者不可互换
test-driven-development 要求先写测试再写实现，systematic-debugging 要求先诊断再修复
subagent-driven-development 处理当前会话内独立任务，dispatching-parallel-agents 处理跨会话或外部并行任务
canvas-design 生成静态视觉艺术文件，frontend-design 生成可交互web界面代码
receiving-code-review 强调质疑精神和验证而非盲从，requesting-code-review 强调主动寻求确认
verification-before-completion 是所有完成声明的前置门禁，evidence-before-assertions是铁律
</EXTREMELY-IMPORTANT>
</system-reminder>