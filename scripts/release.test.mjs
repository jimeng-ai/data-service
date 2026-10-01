// 版本号与上线脚本的单测：node --test scripts/release.test.mjs（只用 Node 自带的模块，Node 18 起可跑）。
// 打 tag、上线前提、上线记录都在临时建的真 git 仓库上测（带一个本地的「远端」裸仓库），不用假对象。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  bumpKind,
  compareVersions,
  deployBlockers,
  latestVersionTag,
  nextVersion,
  parseVersion,
  planTag,
  pushTag,
  releaseNotes,
  resolveDeployTag,
  retrying,
  runningDeploys,
} from './release.mjs';

const git = (cwd, ...args) =>
  execFileSync('git', ['-c', 'init.defaultBranch=main', ...args], {
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
  // 打带说明的 tag 要有署名；CI 里由 tag.yml 配，这里配在临时仓库上。
  git(work, 'config', 'user.name', 't');
  git(work, 'config', 'user.email', 't@t');
  const commit = (message, file = 'a.txt') => {
    writeFileSync(join(work, file), message);
    git(work, 'add', '.');
    git(work, 'commit', '-q', '-m', message);
  };
  const tag = (name, push = false) => {
    git(work, 'tag', '-a', name, '-m', name);
    if (push) git(work, 'push', '-q', 'origin', name);
  };
  commit('init');
  git(work, 'push', '-q', 'origin', 'main');
  return { root, remote, work, commit, tag };
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

test('这次加哪一位：不兼容改动加第一位，新功能加中间那位，其余加最后一位', () => {
  assert.equal(bumpKind(['fix: 修一个问题', 'docs: 改文档']), 'patch');
  assert.equal(bumpKind(['fix: 修一个问题', 'feat(graph): 新功能']), 'minor');
  assert.equal(bumpKind(['feat：全角冒号也算']), 'minor');
  assert.equal(bumpKind(['refactor(api)!: 改接口']), 'major');
  assert.equal(bumpKind(['feat!: 去掉旧接口', 'fix: 别的']), 'major');
  assert.equal(bumpKind(['fix: 改字段\n\nBREAKING CHANGE: 旧字段没了']), 'major');
  assert.equal(bumpKind(['微调', '各项功能完成']), 'patch', '不按约定写的提交按修问题算');
  assert.equal(bumpKind(['feature: 不是约定里的 feat']), 'patch');
  assert.equal(bumpKind([]), 'patch', '只有合并提交的推送也要有 tag');
});

test('下一个版本号：在最大的版本号上加；一个都没有时从 1.0.0 开始', () => {
  assert.equal(nextVersion('v1.2.3', 'patch'), 'v1.2.4');
  assert.equal(nextVersion('v1.2.3', 'minor'), 'v1.3.0');
  assert.equal(nextVersion('v1.2.3', 'major'), 'v2.0.0');
  assert.equal(nextVersion(null, 'minor'), 'v1.0.0');
});

test('打 tag：按上一个版本以来的提交算版本号，说明里列出这些提交（不含合并提交）', (t) => {
  const { work, commit, tag } = repo(t);
  tag('v1.0.0');
  commit('fix: 修一个问题');
  git(work, 'checkout', '-q', '-b', 'side');
  commit('feat(graph): 旁支上的新功能', 'c.txt');
  git(work, 'checkout', '-q', 'main');
  git(work, 'merge', '-q', '--no-ff', '-m', 'Merge branch side', 'side');

  const plan = planTag({ cwd: work, name: 'data-service' });

  assert.equal(plan.tag, 'v1.1.0');
  assert.equal(plan.previous, 'v1.0.0');
  assert.match(plan.message, /^data-service v1\.1\.0\n/);
  assert.match(plan.message, /- feat\(graph\): 旁支上的新功能/);
  assert.match(plan.message, /- fix: 修一个问题/);
  assert.doesNotMatch(plan.message, /Merge branch/);
});

test('打 tag：这个提交已经有版本号就不再打（重跑、main 退回旧提交都不会多打）', (t) => {
  const { work, commit, tag } = repo(t);
  commit('feat: 新功能');
  tag('v1.1.0');

  const plan = planTag({ cwd: work, name: 'x' });

  assert.equal(plan.tag, null);
  assert.match(plan.skip, /v1\.1\.0/);
});

test('打 tag：在全仓库最大的版本上加，不和别的线上已有的 tag 撞号；说明只列这条线上新增的', (t) => {
  const { work, commit, tag } = repo(t);
  tag('v1.0.0');
  git(work, 'checkout', '-q', '-b', 'old-line');
  commit('fix: 另一条线上的修复', 'c.txt');
  tag('v1.4.0');
  git(work, 'checkout', '-q', 'main');
  commit('fix: 主线上的修复');

  const plan = planTag({ cwd: work, name: 'x' });

  assert.equal(plan.tag, 'v1.4.1');
  assert.equal(plan.previous, 'v1.0.0');
  assert.match(plan.message, /主线上的修复/);
  assert.doesNotMatch(plan.message, /另一条线上的修复/);
});

test('打 tag：只合并了已经有版本号的分支，这次推送也有自己的 tag（加最后一位）', (t) => {
  const { work, commit, tag } = repo(t);
  tag('v1.0.0');
  git(work, 'checkout', '-q', '-b', 'side');
  commit('feat: 旁支上的新功能', 'c.txt');
  tag('v1.1.0');
  git(work, 'checkout', '-q', 'main');
  git(work, 'merge', '-q', '--no-ff', '-m', 'Merge branch side', 'side');

  assert.equal(planTag({ cwd: work, name: 'x' }).tag, 'v1.1.1');
});

test('推 tag：在 HEAD 上打带说明的 tag，推到远端', (t) => {
  const { remote, work, commit, tag } = repo(t);
  tag('v1.0.0', true);
  commit('feat: 新功能');
  git(work, 'push', '-q', 'origin', 'main');

  pushTag({ cwd: work, plan: planTag({ cwd: work, name: 'x' }) });

  assert.equal(git(remote, 'cat-file', '-t', 'v1.1.0'), 'tag', '带说明的 tag，不是轻量 tag');
  assert.equal(git(remote, 'rev-parse', 'v1.1.0^{commit}'), git(work, 'rev-parse', 'HEAD'));
  assert.match(git(remote, 'tag', '-l', '--format=%(contents)', 'v1.1.0'), /- feat: 新功能/);
});

test('推 tag：远端已经有同名 tag 时报错，本地也不留下这个 tag（下次重跑能重新算）', (t) => {
  const { root, remote, work, commit, tag } = repo(t);
  tag('v1.0.0', true);
  commit('fix: 本地的修复');
  git(work, 'push', '-q', 'origin', 'main');
  const plan = planTag({ cwd: work, name: 'x' });
  const other = join(root, 'other');
  git(root, 'clone', '-q', remote, other);
  git(other, 'tag', 'v1.0.1');
  git(other, 'push', '-q', 'origin', 'v1.0.1');

  assert.throws(() => pushTag({ cwd: work, plan }));
  assert.equal(git(work, 'tag', '-l', 'v1.0.1'), '');
});

test('上哪个版本：写明的照用（v 可带可不带）；没写就上 main 上最大的', () => {
  assert.equal(resolveDeployTag('1.2.0', ['v1.0.0', 'v1.2.0']), 'v1.2.0');
  assert.equal(resolveDeployTag('v1.2.0', ['v1.2.0']), 'v1.2.0');
  assert.equal(resolveDeployTag(undefined, ['v1.0.0', 'v1.3.0', 'v1.2.9', 'nightly']), 'v1.3.0');
  assert.throws(() => resolveDeployTag(undefined, []), /还没有任何版本/);
  assert.throws(() => resolveDeployTag('1.2', ['v1.0.0']), /版本号/);
});

test('上线前提：版本在远端 main 上、本地就停在这个版本、工作区干净，才放行', (t) => {
  const { work, commit, tag } = repo(t);
  commit('feat: 新功能');
  git(work, 'push', '-q', 'origin', 'main');
  tag('v1.1.0', true);

  assert.deepEqual(deployBlockers({ cwd: work, tag: 'v1.1.0' }), []);
});

test('上线前提：远端还没有这个版本（等 Tag 流水线打完）', (t) => {
  const { work, tag } = repo(t);
  tag('v1.0.0');

  const blockers = deployBlockers({ cwd: work, tag: 'v1.0.0' });

  assert.ok(blockers.some((b) => b.includes('远端没有 v1.0.0')), blockers.join('\n'));
});

test('上线前提：本地不在这个版本上（检查要在要上线的代码上跑）', (t) => {
  const { work, commit, tag } = repo(t);
  tag('v1.0.0', true);
  commit('fix: 之后的提交');
  git(work, 'push', '-q', 'origin', 'main');

  const blockers = deployBlockers({ cwd: work, tag: 'v1.0.0' });

  assert.ok(blockers.some((b) => b.includes('git checkout v1.0.0')), blockers.join('\n'));
});

test('上线前提：工作区有没提交的改动', (t) => {
  const { work, tag } = repo(t);
  tag('v1.0.0', true);
  writeFileSync(join(work, 'a.txt'), 'changed');

  const blockers = deployBlockers({ cwd: work, tag: 'v1.0.0' });

  assert.ok(blockers.some((b) => b.includes('没提交')), blockers.join('\n'));
});

test('上线前提：这个版本不在 main 上', (t) => {
  const { work, commit, tag } = repo(t);
  git(work, 'checkout', '-q', '-b', 'side');
  commit('feat: 只在旁支上', 'c.txt');
  tag('v1.1.0', true);

  const blockers = deployBlockers({ cwd: work, tag: 'v1.1.0' });

  assert.ok(blockers.some((b) => b.includes('不在 main 上')), blockers.join('\n'));
});

test('上线记录：列出上一次上线以来的提交（不含合并提交），附对比链接', (t) => {
  const { work, commit, tag } = repo(t);
  tag('v1.0.0');
  commit('feat(graph): 数据星图改为对象地图');
  git(work, 'checkout', '-q', '-b', 'side');
  commit('fix: 旁支上的修复', 'c.txt');
  git(work, 'checkout', '-q', 'main');
  git(work, 'merge', '-q', '--no-ff', '-m', 'Merge branch side', 'side');
  tag('v1.1.0');

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

test('上线记录：列的是上线的那个版本以内的提交，本地多出来的不算', (t) => {
  const { work, commit, tag } = repo(t);
  tag('v1.0.0');
  commit('fix: 这一版里的修复');
  tag('v1.0.1');
  commit('feat: 还没上线的新功能');

  const notes = releaseNotes({ cwd: work, previousTag: 'v1.0.0', tag: 'v1.0.1', repoUrl: null });

  assert.match(notes, /- fix: 这一版里的修复/);
  assert.doesNotMatch(notes, /还没上线的新功能/);
});

test('上线记录：第一次上线只写一句，不把全部历史倒出来', (t) => {
  const { work } = repo(t);
  const notes = releaseNotes({ cwd: work, previousTag: null, tag: 'v1.0.0', repoUrl: null });
  assert.match(notes, /第一条上线记录/);
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
