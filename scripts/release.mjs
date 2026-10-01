#!/usr/bin/env node
// 发版：给远端 main 上的这一版打 vX.Y.Z 的 tag 并推上去。推 tag 就是部署：.github/workflows/deploy.yml 只认 v* tag，
// 推 main 不再上线。五个仓库（data-service / jm-agent-front / jm-admin / jm-operator / jm-agent-sandbox）用的是同一份脚本，
// 逐字节相同，改一处就五处一起改；各仓库自己的东西（名字、发版前要过的检查）写在仓库根目录的 release.json。
//
// 用法：node scripts/release.mjs <版本号 | major | minor | patch> [--notes-file 文件] [--dry-run] [--no-wait]
//   版本号：新功能加中间那位（1.0.0 → 1.1.0），只修问题加最后一位（1.0.0 → 1.0.1），
//           要几个仓库一起改的不兼容改动加第一位（1.4.2 → 2.0.0）。major / minor / patch 是在上一个版本上加。
//   --notes-file  用写好的发布说明（比如上线前要先执行 DDL、依赖别的仓库的哪个版本）；不给就按上一个版本以来的提交生成。
//   --dry-run     只查前提、跑检查、打印发布说明，不打 tag、不推、不部署。
//   --no-wait     推完 tag 不等部署跑完。
//
// 发版前提，不满足就停，一次全列出来：在 main 上；工作区干净；本地 main 与远端一致（发的只能是远端 main 上的代码）；
// tag 没用过；版本号比上一个版本大；五个仓库都没有部署在跑（共用一台 runner，并发构建会随机失败）。
// 前提都满足后依次跑 release.json 里的检查，全过才打 tag。
// 回滚：GitHub → Actions → Deploy → Run workflow，选上一个 tag。
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const OWNER = 'jimeng-ai';
/** 共用同一台自托管 runner 的仓库：发版前确认它们都没有部署在跑。 */
const SHARED_RUNNER_REPOS = ['data-service', 'jm-agent-front', 'jm-admin', 'jm-operator', 'jm-agent-sandbox'];
/** 只认 1.2.3 / v1.2.3：不带预发布后缀，数字不带前导零。 */
const VERSION = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

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

/** 上一个版本：只看 vX.Y.Z 这种 tag，取最大的；一个都没有为 null。 */
export function latestVersionTag(tags) {
  let best = null;
  for (const tag of tags) {
    if (!tag.startsWith('v') || !parseVersion(tag)) continue;
    if (best === null || compareVersions(tag, best) > 0) best = tag;
  }
  return best;
}

/** 这次的版本号：写明的照用（v 可带可不带）；major / minor / patch 在上一个版本上加。 */
export function resolveVersion(arg, latestTag) {
  if (arg === 'major' || arg === 'minor' || arg === 'patch') {
    if (!latestTag) throw new Error('还没有任何版本：第一次发版请写明版本号，比如 1.0.0');
    const v = parseVersion(latestTag);
    const next =
      arg === 'major'
        ? [v.major + 1, 0, 0]
        : arg === 'minor'
          ? [v.major, v.minor + 1, 0]
          : [v.major, v.minor, v.patch + 1];
    const version = next.join('.');
    return { version, tag: `v${version}` };
  }
  const v = parseVersion(arg);
  if (!v) throw new Error(`版本号要写成 1.2.3（或者 major / minor / patch）：现在是 ${arg ?? '（没给）'}`);
  const version = `${v.major}.${v.minor}.${v.patch}`;
  return { version, tag: `v${version}` };
}

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

/** 发版前提，返回所有不满足的（空数组 = 可以发）。会先 fetch 远端 main 和 tag。 */
export function releaseBlockers({ cwd, tag, latestTag }) {
  const blockers = [];
  const branch = git(cwd, 'rev-parse', '--abbrev-ref', 'HEAD');
  if (branch !== 'main') blockers.push(`要在 main 上发版：现在在 ${branch}`);
  const dirty = git(cwd, 'status', '--porcelain');
  if (dirty) blockers.push(`工作区有没提交的改动：先提交（或收起来）再发版\n${dirty}`);
  git(cwd, 'fetch', '--quiet', '--tags', 'origin', '+refs/heads/main:refs/remotes/origin/main');
  if (branch === 'main') {
    const [ahead, behind] = git(cwd, 'rev-list', '--left-right', '--count', 'HEAD...origin/main')
      .split(/\s+/)
      .map(Number);
    if (ahead > 0) blockers.push(`本地 main 有 ${ahead} 个提交还没推送：发的只能是远端 main 上的代码，先 git push`);
    if (behind > 0) blockers.push(`本地 main 落后远端 ${behind} 个提交：先 git pull`);
  }
  if (git(cwd, 'tag', '-l', tag) || git(cwd, 'ls-remote', '--tags', 'origin', `refs/tags/${tag}`)) {
    blockers.push(`${tag} 已经存在：一个版本号只发一次，换一个更大的`);
  }
  if (latestTag && compareVersions(tag, latestTag) <= 0) {
    blockers.push(`版本号要比上一个版本 ${latestTag} 大：现在是 ${tag}`);
  }
  return blockers;
}

/** 自动生成的发布说明：上一个版本以来的提交标题（不含合并提交），附对比链接。第一个版本只写一句。 */
export function releaseNotes({ cwd, previousTag, tag, repoUrl }) {
  if (!previousTag) return `第一个版本：从这一版（${tag}）开始记版本号。`;
  const log = git(cwd, 'log', '--no-merges', '--format=- %s', `${previousTag}..HEAD`);
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

function parseArgs(argv) {
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

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const cwd = git(process.cwd(), 'rev-parse', '--show-toplevel');
  const configFile = join(cwd, 'release.json');
  if (!existsSync(configFile)) throw new Error('仓库根目录缺 release.json（仓库名、发版前要跑的检查）');
  const config = JSON.parse(readFileSync(configFile, 'utf-8'));

  git(cwd, 'fetch', '--quiet', '--tags', 'origin');
  const latestTag = latestVersionTag(git(cwd, 'tag', '-l', 'v*').split('\n').filter(Boolean));
  const { tag } = resolveVersion(args.version, latestTag);
  const blockers = releaseBlockers({ cwd, tag, latestTag });

  let ghReady = true;
  try {
    retrying(() => gh(['auth', 'status'], cwd));
  } catch {
    ghReady = false;
    blockers.push('gh 没装或没登录：先 gh auth login（建发布说明、看部署进度要用）');
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
        blockers.push(`查不到 ${repo} 的部署状态（重试 3 次都没成），没法确认 runner 空闲：${e.message}`);
      }
    }
    const busy = runningDeploys(runs);
    if (busy.length) {
      blockers.push(`有部署还没跑完，等它结束再发（五个仓库共用一台 runner，并发构建会随机失败）：\n${busy.join('\n')}`);
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
  const notes = args.notesFile
    ? readFileSync(resolve(process.cwd(), args.notesFile), 'utf-8')
    : releaseNotes({ cwd, previousTag: latestTag, tag, repoUrl: repoUrlOf(cwd) });
  if (args.dryRun) {
    console.log(`\n（演练，什么都没动）会给 ${git(cwd, 'rev-parse', '--short', 'HEAD')} 打 ${tag} 并推送，推送即部署。发布说明：\n\n# ${title}\n${notes}`);
    return;
  }

  git(cwd, 'tag', '-a', tag, '-m', title);
  git(cwd, 'push', '--quiet', 'origin', tag);
  console.log(`✅ 已推送 ${tag}，部署已触发。回滚：GitHub → Actions → Deploy → Run workflow，选上一个 tag。`);
  const dir = mkdtempSync(join(tmpdir(), 'release-notes-'));
  try {
    const file = join(dir, 'notes.md');
    writeFileSync(file, notes);
    gh(['release', 'create', tag, '--title', title, '--notes-file', file], cwd);
  } catch (e) {
    console.error(`⚠️ ${tag} 已推送、部署已触发，但发布说明没建成，稍后在 GitHub 上补：${errorLine(e)}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
  if (!args.wait) return;

  let runId = null;
  for (let i = 0; i < 30 && runId === null; i += 1) {
    const list = JSON.parse(
      retrying(() => gh(['run', 'list', '--workflow', 'deploy.yml', '--branch', tag, '--limit', '1', '--json', 'databaseId'], cwd)),
    );
    runId = list[0]?.databaseId ?? null;
    if (runId === null) await new Promise((r) => setTimeout(r, 2000));
  }
  if (runId === null) {
    console.error(`⚠️ 60 秒内没看到 ${tag} 的部署，去 GitHub Actions 看一眼`);
    process.exit(1);
  }
  const watch = spawnSync('gh', ['run', 'watch', String(runId), '--exit-status'], { cwd, stdio: 'inherit' });
  process.exit(watch.status ?? 1);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(`❌ ${e.message}`);
    process.exit(2);
  });
}
