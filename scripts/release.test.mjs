// 发版脚本的单测：node --test scripts/release.test.mjs（只用 Node 自带的模块，Node 18 起可跑）。
// 拦截条件和发布说明都在临时建的真 git 仓库上测（带一个本地的「远端」裸仓库），不用假对象。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  compareVersions,
  latestVersionTag,
  parseVersion,
  releaseBlockers,
  releaseNotes,
  resolveVersion,
  retrying,
  runningDeploys,
} from './release.mjs';

const git = (cwd, ...args) =>
  execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'init.defaultBranch=main', ...args], {
    cwd,
    encoding: 'utf-8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();

/** 一个推过 main 的工作仓库 + 它的「远端」裸仓库；用完删掉。 */
function repo(t) {
  const root = mkdtempSync(join(tmpdir(), 'release-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const remote = join(root, 'remote.git');
  const work = join(root, 'work');
  git(root, 'init', '--bare', '-q', remote);
  git(root, 'clone', '-q', remote, work);
  const commit = (subject, file = 'a.txt') => {
    writeFileSync(join(work, file), subject);
    git(work, 'add', '.');
    git(work, 'commit', '-q', '-m', subject);
  };
  commit('init');
  git(work, 'push', '-q', 'origin', 'main');
  return { root, remote, work, commit };
}

test('版本号：只认 1.2.3 / v1.2.3，按数字比大小', () => {
  assert.deepEqual(parseVersion('1.2.3'), { major: 1, minor: 2, patch: 3 });
  assert.deepEqual(parseVersion('v10.0.7'), { major: 10, minor: 0, patch: 7 });
  for (const bad of ['1.2', '1.2.3.4', 'v1.2.3-rc1', '01.2.3', 'abc', '', null]) {
    assert.equal(parseVersion(bad), null, String(bad));
  }
  assert.equal(compareVersions('1.10.0', '1.9.0'), 1, '按数字比，不按字符串');
  assert.equal(compareVersions('v1.2.0', '1.2.0'), 0);
  assert.equal(compareVersions('1.2.0', '2.0.0'), -1);
});

test('上一个版本：只看 vX.Y.Z 这种 tag，取最大的', () => {
  assert.equal(latestVersionTag(['v1.0.0', 'v1.10.0', 'v1.9.3', 'nightly', 'v2.0.0-rc1']), 'v1.10.0');
  assert.equal(latestVersionTag(['nightly']), null);
  assert.equal(latestVersionTag([]), null);
});

test('这次的版本号：写明的照用；major / minor / patch 在上一个版本上加', () => {
  assert.deepEqual(resolveVersion('1.1.0', 'v1.0.0'), { version: '1.1.0', tag: 'v1.1.0' });
  assert.deepEqual(resolveVersion('v1.1.0', null), { version: '1.1.0', tag: 'v1.1.0' });
  assert.deepEqual(resolveVersion('minor', 'v1.2.3'), { version: '1.3.0', tag: 'v1.3.0' });
  assert.deepEqual(resolveVersion('patch', 'v1.2.3'), { version: '1.2.4', tag: 'v1.2.4' });
  assert.deepEqual(resolveVersion('major', 'v1.2.3'), { version: '2.0.0', tag: 'v2.0.0' });
  assert.throws(() => resolveVersion('minor', null), /还没有任何版本/);
  assert.throws(() => resolveVersion('1.2', 'v1.0.0'), /版本号/);
  assert.throws(() => resolveVersion(undefined, 'v1.0.0'), /版本号/);
});

test('拦截条件：在 main 上、干净、和远端一致、tag 没用过、比上一个版本大，才放行', (t) => {
  const { work } = repo(t);
  assert.deepEqual(releaseBlockers({ cwd: work, tag: 'v1.0.0', latestTag: null }), []);
});

test('拦截条件：不在 main 上', (t) => {
  const { work } = repo(t);
  git(work, 'checkout', '-q', '-b', 'feature');
  const blockers = releaseBlockers({ cwd: work, tag: 'v1.0.0', latestTag: null });
  assert.ok(blockers.some((b) => b.includes('main') && b.includes('feature')), blockers.join('\n'));
});

test('拦截条件：工作区有没提交的改动', (t) => {
  const { work } = repo(t);
  writeFileSync(join(work, 'a.txt'), 'changed');
  const blockers = releaseBlockers({ cwd: work, tag: 'v1.0.0', latestTag: null });
  assert.ok(blockers.some((b) => b.includes('没提交')), blockers.join('\n'));
});

test('拦截条件：本地有没推送的提交（发的只能是远端 main 上的代码）', (t) => {
  const { work, commit } = repo(t);
  commit('local only');
  const blockers = releaseBlockers({ cwd: work, tag: 'v1.0.0', latestTag: null });
  assert.ok(blockers.some((b) => b.includes('没推送')), blockers.join('\n'));
});

test('拦截条件：远端 main 有本地没拉的提交', (t) => {
  const { root, remote, work } = repo(t);
  const other = join(root, 'other');
  git(root, 'clone', '-q', remote, other);
  writeFileSync(join(other, 'b.txt'), 'from elsewhere');
  git(other, 'add', '.');
  git(other, 'commit', '-q', '-m', 'from elsewhere');
  git(other, 'push', '-q', 'origin', 'main');
  const blockers = releaseBlockers({ cwd: work, tag: 'v1.0.0', latestTag: null });
  assert.ok(blockers.some((b) => b.includes('落后')), blockers.join('\n'));
});

test('拦截条件：tag 已经用过（本地或远端），版本号没比上一个版本大', (t) => {
  const { work } = repo(t);
  git(work, 'tag', '-a', 'v1.0.0', '-m', 'v1.0.0');
  git(work, 'push', '-q', 'origin', 'v1.0.0');
  const same = releaseBlockers({ cwd: work, tag: 'v1.0.0', latestTag: 'v1.0.0' });
  assert.ok(same.some((b) => b.includes('已经存在')), same.join('\n'));
  const older = releaseBlockers({ cwd: work, tag: 'v0.9.0', latestTag: 'v1.0.0' });
  assert.ok(older.some((b) => b.includes('要比上一个版本 v1.0.0 大')), older.join('\n'));
});

test('发布说明：列出上一个版本以来的提交（不含合并提交），附对比链接', (t) => {
  const { work, commit } = repo(t);
  git(work, 'tag', '-a', 'v1.0.0', '-m', 'v1.0.0');
  commit('feat(graph): 数据星图改为对象地图');
  git(work, 'checkout', '-q', '-b', 'side');
  commit('fix: 旁支上的修复', 'c.txt');
  git(work, 'checkout', '-q', 'main');
  git(work, 'merge', '-q', '--no-ff', '-m', 'Merge branch side', 'side');
  const notes = releaseNotes({
    cwd: work,
    previousTag: 'v1.0.0',
    tag: 'v1.1.0',
    repoUrl: 'https://github.com/jimeng-ai/data-service',
  });
  assert.match(notes, /- feat\(graph\): 数据星图改为对象地图/);
  assert.match(notes, /- fix: 旁支上的修复/);
  assert.doesNotMatch(notes, /Merge branch/);
  assert.match(notes, /https:\/\/github\.com\/jimeng-ai\/data-service\/compare\/v1\.0\.0\.\.\.v1\.1\.0/);
});

test('发布说明：第一个版本只写「开始记版本」，不把全部历史倒出来', (t) => {
  const { work } = repo(t);
  const notes = releaseNotes({ cwd: work, previousTag: null, tag: 'v1.0.0', repoUrl: null });
  assert.match(notes, /第一个版本/);
  assert.doesNotMatch(notes, /- init/);
});

test('有部署正在跑：排队中、进行中的都算，跑完的不算', () => {
  const found = runningDeploys({
    'data-service': [
      { status: 'in_progress', headBranch: 'v1.1.0', url: 'u1' },
      { status: 'completed', headBranch: 'v1.0.0', url: 'u0' },
    ],
    'jm-admin': [{ status: 'queued', headBranch: 'v1.0.1', url: 'u2' }],
    'jm-operator': [{ status: 'completed', headBranch: 'main', url: 'u3' }],
  });
  assert.deepEqual(found, ['data-service v1.1.0（in_progress）u1', 'jm-admin v1.0.1（queued）u2']);
});

test('连 GitHub 偶尔超时：重试几次；一直失败就报最后一次的真实原因（stderr 第一行），不是只报命令', () => {
  let calls = 0;
  const value = retrying(() => {
    calls += 1;
    if (calls < 3) throw new Error('boom');
    return 'ok';
  }, { attempts: 3, delayMs: 1 });
  assert.equal(value, 'ok');
  assert.equal(calls, 3);

  let tries = 0;
  const timeout = Object.assign(new Error('Command failed: gh run list --repo jimeng-ai/jm-admin'), {
    stderr: '\nGet "https://api.github.com/repos/jimeng-ai/jm-admin": net/http: TLS handshake timeout\n',
  });
  assert.throws(() => retrying(() => {
    tries += 1;
    throw timeout;
  }, { attempts: 2, delayMs: 1 }), /TLS handshake timeout/);
  assert.equal(tries, 2);
});
