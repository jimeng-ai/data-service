#!/usr/bin/env node
// 版本号与上线。五个仓库（data-service / jm-agent-front / jm-admin / jm-operator / jm-agent-sandbox）用的是同一份脚本，
// 逐字节相同，改一处就五处一起改；各仓库自己的东西（名字、上线前要过的检查）写在仓库根目录的 release.json。
//
// 打 tag 和上线是两件事：
// - 打 tag：每次推 main，.github/workflows/tag.yml 跑 `node scripts/release.mjs tag`，给推上去的那个提交打下一个版本号的
//   tag。只打 tag，不部署，也不建 GitHub Release。版本号在全仓库最大的版本上加，加哪一位看上一个版本以来新增的提交：
//   有不兼容改动（`类型!:`，或正文里写了 BREAKING CHANGE）加第一位，有新功能（feat）加中间那位，其余加最后一位。
// - 上线：什么时候上由人决定。GitHub → Actions → Deploy → Run workflow，在 Use workflow from 里选要上线的 tag；
//   或者本地 `node scripts/release.mjs deploy [版本号]`（npm 仓库是 `npm run deploy -- [版本号]`），不写版本号就上 main 上
//   最新的。本地这条会先确认五个仓库都没有部署在跑（共用一台机器，并发构建会随机失败），再跑 release.json 里的检查、
//   触发部署、等它跑完；成功后写一条 GitHub Release 当上线记录，并标成 Latest。回滚：同样选上一个 tag 再上一次。
//
// 用法：
//   node scripts/release.mjs tag
//   node scripts/release.mjs deploy [版本号] [--notes-file 文件] [--dry-run] [--no-wait]
//     --notes-file  上线记录用写好的说明（比如上线前要先执行 DDL）；不给就列出上一次上线以来的提交。
//     --dry-run     只查前提、跑检查、打印上线记录，不触发部署。
//     --no-wait     触发部署后不等它跑完（也就不写上线记录）。
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const OWNER = 'jimeng-ai';
/** 共用同一台机器上的自托管 runner 的仓库：上线前确认它们都没有部署在跑。 */
const SHARED_RUNNER_REPOS = ['data-service', 'jm-agent-front', 'jm-admin', 'jm-operator', 'jm-agent-sandbox'];
/** 只认 1.2.3 / v1.2.3：不带预发布后缀，数字不带前导零。 */
const VERSION = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
/** 提交标题的约定写法：`类型(范围)!: 说明`，冒号全角半角都认。 */
const BREAKING_SUBJECT = /^[A-Za-z]+(\([^)]*\))?!\s*[:：]/;
const BREAKING_BODY = /^BREAKING[ -]CHANGE\s*[:：]/m;
const FEATURE_SUBJECT = /^feat(\([^)]*\))?\s*[:：]/;

export function parseVersion(text) {
  const m = VERSION.exec(typeof text === 'string' ? text : '');
  return m ? { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]) } : null;
}

/** 按数字比：1.10.0 比 1.9.0 大。 */
export function compareVersions(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  for (const key of ['major', 'minor', 'patch']) {
    if (x[key] !== y[key]) return x[key] > y[key] ? 1 : -1;
  }
  return 0;
}

/** 最大的版本：只看 vX.Y.Z 这种 tag；一个都没有为 null。 */
export function latestVersionTag(tags) {
  let best = null;
  for (const tag of tags) {
    if (!tag.startsWith('v') || !parseVersion(tag)) continue;
    if (best === null || compareVersions(tag, best) > 0) best = tag;
  }
  return best;
}

/** 这次加哪一位。messages 是新增提交的完整说明（标题 + 正文）；不按约定写的提交按修问题算。 */
export function bumpKind(messages) {
  let kind = 'patch';
  for (const message of messages) {
    const subject = message.split('\n')[0].trim();
    if (BREAKING_SUBJECT.test(subject) || BREAKING_BODY.test(message)) return 'major';
    if (FEATURE_SUBJECT.test(subject)) kind = 'minor';
  }
  return kind;
}

/** 在最大的版本号上加一位；一个版本都没有时从 v1.0.0 开始。 */
export function nextVersion(highest, kind) {
  if (!highest) return 'v1.0.0';
  const v = parseVersion(highest);
  const next =
    kind === 'major'
      ? [v.major + 1, 0, 0]
      : kind === 'minor'
        ? [v.major, v.minor + 1, 0]
        : [v.major, v.minor, v.patch + 1];
  return `v${next.join('.')}`;
}

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

const nonEmptyLines = (text) => text.split('\n').filter(Boolean);

/**
 * 给 HEAD 算这次的 tag。HEAD 已经有版本号时 tag 为 null、skip 写原因：同一次推送重跑、或者 main 退回旧提交，都不多打。
 * 版本号在全仓库最大的版本上加（不和别的线上已有的 tag 撞号）；加哪一位、说明里列哪些提交，看这条线上一个版本以来新增的。
 */
export function planTag({ cwd, name }) {
  const own = latestVersionTag(nonEmptyLines(git(cwd, 'tag', '--points-at', 'HEAD', '-l', 'v*')));
  if (own) return { tag: null, skip: `这个提交已经是 ${own}，不再打` };
  const highest = latestVersionTag(nonEmptyLines(git(cwd, 'tag', '-l', 'v*')));
  const previous = latestVersionTag(nonEmptyLines(git(cwd, 'tag', '--merged', 'HEAD', '-l', 'v*')));
  if (!previous) {
    const tag = nextVersion(highest, 'patch');
    return { tag, previous: null, message: `${name} ${tag}\n\n第一个版本：从这里开始记版本号。` };
  }
  const messages = git(cwd, 'log', '--no-merges', '--format=%B%x00', `${previous}..HEAD`)
    .split('\0')
    .map((message) => message.trim())
    .filter(Boolean);
  const tag = nextVersion(highest, bumpKind(messages));
  const changes = messages.length
    ? messages.map((message) => `- ${message.split('\n')[0].trim()}`)
    : ['- （只有合并提交）'];
  return { tag, previous, message: [`${name} ${tag}`, '', ...changes].join('\n') };
}

/** 在 HEAD 上打带说明的 tag 并推到远端。推不上去（比如远端已有同名 tag）就删掉本地这个 tag 再报错，下次重跑能重新算。 */
export function pushTag({ cwd, plan }) {
  git(cwd, 'tag', '-a', plan.tag, '-m', plan.message);
  try {
    git(cwd, 'push', '--quiet', 'origin', `refs/tags/${plan.tag}`);
  } catch (e) {
    git(cwd, 'tag', '-d', plan.tag);
    throw new Error(`${plan.tag} 推不上去：${errorLine(e)}`);
  }
}

/** 上哪个版本：写明的照用（v 可带可不带）；没写就上 tags 里最大的（调用方给的是已经在远端 main 上的版本）。 */
export function resolveDeployTag(arg, tags) {
  if (arg === undefined) {
    const latest = latestVersionTag(tags);
    if (!latest) throw new Error('main 上还没有任何版本号：推一次 main，Tag 流水线会自动打');
    return latest;
  }
  const v = parseVersion(arg);
  if (!v) throw new Error(`版本号要写成 1.2.3 或 v1.2.3：现在是 ${arg}`);
  return `v${v.major}.${v.minor}.${v.patch}`;
}

/** 上线前提，返回所有不满足的（空数组 = 可以上）。会先 fetch 远端 main 和 tag。 */
export function deployBlockers({ cwd, tag }) {
  if (!git(cwd, 'ls-remote', '--tags', 'origin', `refs/tags/${tag}`)) {
    return [`远端没有 ${tag}：每次推 main 都会自动打 tag，等 GitHub 上的 Tag 流水线跑完再来`];
  }
  git(cwd, 'fetch', '--quiet', '--force', '--tags', 'origin', '+refs/heads/main:refs/remotes/origin/main');
  const blockers = [];
  const commit = git(cwd, 'rev-parse', `${tag}^{commit}`);
  try {
    git(cwd, 'merge-base', '--is-ancestor', commit, 'origin/main');
  } catch {
    blockers.push(`${tag} 不在 main 上：只上线 main 上的版本`);
  }
  if (git(cwd, 'rev-parse', 'HEAD') !== commit) {
    blockers.push(`本地不在 ${tag} 上：上线前的检查要在要上线的代码上跑，先 git checkout ${tag}（上最新版就 git pull）`);
  }
  const dirty = git(cwd, 'status', '--porcelain');
  if (dirty) blockers.push(`工作区有没提交的改动：先提交（或收起来）再上线\n${dirty}`);
  return blockers;
}

/** 上线记录：上一次上线以来、到这一版为止的提交标题（不含合并提交），附对比链接。第一次上线只写一句。 */
export function releaseNotes({ cwd, previousTag, tag, repoUrl }) {
  if (!previousTag) return `第一条上线记录：${tag}。`;
  const log = git(cwd, 'log', '--no-merges', '--format=- %s', `${previousTag}..${tag}`);
  const lines = ['## 这一版的改动', '', log || '- （没有新提交）'];
  if (repoUrl) lines.push('', `完整对比：${repoUrl}/compare/${previousTag}...${tag}`);
  return lines.join('\n');
}

/** 还没跑完的部署（排队中、进行中都算），一条一行：「仓库 tag（状态）链接」。 */
export function runningDeploys(runsByRepo) {
  const out = [];
  for (const [repo, runs] of Object.entries(runsByRepo)) {
    for (const run of runs ?? []) {
      if (run.status !== 'completed') out.push(`${repo} ${run.headBranch}（${run.status}）${run.url}`);
    }
  }
  return out;
}

/** 有用的那一行报错：命令失败时真正的原因在 stderr 里，message 只有一句「Command failed: …」。 */
function errorLine(e) {
  const fromStderr = String(e?.stderr ?? '')
    .split('\n')
    .map((line) => line.trim())
    .find(Boolean);
  return fromStderr || String(e?.message ?? e).split('\n')[0];
}

/** 连 GitHub 偶尔会超时（TLS 握手超时见过不止一次）：同步重试几次，一直失败就抛最后一次的真实原因。 */
export function retrying(fn, { attempts = 3, delayMs = 2000 } = {}) {
  let last;
  for (let i = 0; i < attempts; i += 1) {
    try {
      return fn();
    } catch (e) {
      last = e;
      if (i < attempts - 1) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, delayMs);
    }
  }
  throw new Error(errorLine(last));
}

function gh(args, cwd) {
  return execFileSync('gh', args, { cwd, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function repoUrlOf(cwd) {
  const m = /github\.com[:/](.+?)(?:\.git)?$/.exec(git(cwd, 'remote', 'get-url', 'origin'));
  return m ? `https://github.com/${m[1]}` : null;
}

function parseDeployArgs(argv) {
  const out = { version: undefined, notesFile: null, dryRun: false, wait: true };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--dry-run') out.dryRun = true;
    else if (a === '--no-wait') out.wait = false;
    else if (a === '--notes-file') out.notesFile = argv[(i += 1)];
    else if (out.version === undefined) out.version = a;
    else throw new Error(`看不懂的参数：${a}`);
  }
  return out;
}

/** CI 用：给这次推送打 tag。先把本地 tag 和远端对齐（自托管 runner 的工作目录会留着上次的东西）。 */
function tagCommand(cwd, config) {
  git(cwd, 'fetch', '--quiet', '--force', '--prune', '--prune-tags', 'origin');
  const plan = planTag({ cwd, name: config.name });
  if (!plan.tag) {
    console.log(plan.skip);
    return;
  }
  pushTag({ cwd, plan });
  console.log(`✅ 已打 ${plan.tag}（${git(cwd, 'rev-parse', '--short', 'HEAD')}），没有部署。\n\n${plan.message}`);
}

/** 上线成功后写上线记录：没上过的版本新建一条 Release；以前上过的（回滚）把那条重新标成 Latest。 */
function recordRelease({ cwd, tag, title, notes }) {
  const dir = mkdtempSync(join(tmpdir(), 'release-notes-'));
  try {
    let recorded = true;
    try {
      gh(['release', 'view', tag, '--json', 'tagName'], cwd);
    } catch {
      recorded = false;
    }
    if (recorded) {
      gh(['release', 'edit', tag, '--latest'], cwd);
    } else {
      const file = join(dir, 'notes.md');
      writeFileSync(file, notes);
      gh(['release', 'create', tag, '--verify-tag', '--latest', '--title', title, '--notes-file', file], cwd);
    }
    console.log(`✅ ${tag} 上线成功，上线记录：${repoUrlOf(cwd)}/releases/tag/${tag}`);
  } catch (e) {
    console.error(`⚠️ ${tag} 已上线，但上线记录没写成，稍后在 GitHub 上补：${errorLine(e)}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

async function deployCommand(cwd, config, args) {
  git(cwd, 'fetch', '--quiet', '--force', '--tags', 'origin', '+refs/heads/main:refs/remotes/origin/main');
  const tag = resolveDeployTag(args.version, nonEmptyLines(git(cwd, 'tag', '--merged', 'origin/main', '-l', 'v*')));
  const blockers = deployBlockers({ cwd, tag });

  let ghReady = true;
  try {
    retrying(() => gh(['auth', 'status'], cwd));
  } catch {
    ghReady = false;
    blockers.push('gh 没装或没登录：先 gh auth login（触发部署、看进度、写上线记录要用）');
  }
  if (ghReady) {
    const runs = {};
    for (const repo of SHARED_RUNNER_REPOS) {
      try {
        runs[repo] = JSON.parse(
          retrying(() =>
            gh(['run', 'list', '--repo', `${OWNER}/${repo}`, '--workflow', 'deploy.yml', '--limit', '5', '--json', 'status,headBranch,url'], cwd),
          ),
        );
      } catch (e) {
        blockers.push(`查不到 ${repo} 的部署状态（重试 3 次都没成），没法确认机器空闲：${e.message}`);
      }
    }
    const busy = runningDeploys(runs);
    if (busy.length) {
      blockers.push(`有部署还没跑完，等它结束再上（五个仓库共用一台机器，并发构建会随机失败）：\n${busy.join('\n')}`);
    }
  }
  if (blockers.length) {
    for (const b of blockers) console.error(`❌ ${b}`);
    process.exit(2);
  }

  for (const command of config.gate ?? []) {
    console.log(`▶ ${command}`);
    const r = spawnSync(command, { cwd, shell: true, stdio: 'inherit' });
    if (r.status !== 0) {
      console.error(`❌ 检查没过：${command}`);
      process.exit(r.status ?? 1);
    }
  }

  const title = `${config.name} ${tag}`;
  let previous = null;
  try {
    previous = retrying(() => gh(['release', 'view', '--json', 'tagName', '--jq', '.tagName'], cwd)) || null;
  } catch {
    previous = null; // 还没有任何上线记录
  }
  const notes = args.notesFile
    ? readFileSync(resolve(process.cwd(), args.notesFile), 'utf-8')
    : releaseNotes({ cwd, previousTag: previous, tag, repoUrl: repoUrlOf(cwd) });
  if (args.dryRun) {
    console.log(`\n（演练，什么都没动）会上线 ${tag}（${git(cwd, 'rev-parse', '--short', `${tag}^{commit}`)}）。上线记录：\n\n# ${title}\n${notes}`);
    return;
  }

  const listRuns = () =>
    JSON.parse(retrying(() => gh(['run', 'list', '--workflow', 'deploy.yml', '--limit', '10', '--json', 'databaseId,headBranch,url'], cwd)));
  const before = Math.max(0, ...listRuns().map((run) => run.databaseId));
  retrying(() => gh(['workflow', 'run', 'deploy.yml', '--ref', tag], cwd));
  console.log(`▶ 已触发上线 ${tag}`);
  let run = null;
  for (let i = 0; i < 30 && run === null; i += 1) {
    run = listRuns().find((r) => r.databaseId > before && r.headBranch === tag) ?? null;
    if (run === null) await new Promise((r) => setTimeout(r, 2000));
  }
  if (run === null) {
    console.error(`⚠️ 60 秒内没看到 ${tag} 的部署，去 GitHub Actions 看一眼`);
    process.exit(1);
  }
  console.log(run.url);
  if (!args.wait) {
    console.log('没等部署跑完，所以没写上线记录；跑成功后用 gh release create 补一条');
    return;
  }
  const watch = spawnSync('gh', ['run', 'watch', String(run.databaseId), '--exit-status'], { cwd, stdio: 'inherit' });
  if (watch.status !== 0) process.exit(watch.status ?? 1);
  recordRelease({ cwd, tag, title, notes });
}

async function main() {
  const [command, ...rest] = process.argv.slice(2);
  const cwd = git(process.cwd(), 'rev-parse', '--show-toplevel');
  const configFile = join(cwd, 'release.json');
  if (!existsSync(configFile)) throw new Error('仓库根目录缺 release.json（仓库名、上线前要跑的检查）');
  const config = JSON.parse(readFileSync(configFile, 'utf-8'));
  if (command === 'tag') return tagCommand(cwd, config);
  if (command === 'deploy') return deployCommand(cwd, config, parseDeployArgs(rest));
  throw new Error('用法：node scripts/release.mjs tag | deploy [版本号] [--notes-file 文件] [--dry-run] [--no-wait]');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(`❌ ${e.message}`);
    process.exit(2);
  });
}
