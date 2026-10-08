#!/usr/bin/env python3
"""把 quick-validate-cases.json 里的每条用例写成一个临时 skill 目录，交给 skill-creator 的 quick_validate.py 判，
再和用例里记的 quickValidate 字段比。全部一致退出 0，否则列出不一致的用例、退出 1。

这条核对不进 CI：data-service 的 CI 里没有沙箱仓库，也不一定有 PyYAML。它保证用例文件里记的
quickValidate 是 quick_validate.py 亲自判出来的，而不是谁凭印象写的；Java 测试再保证严格模式和这份用例一致。
升级 vendor/skill-creator 之后要重跑一次。

用法：python3 scripts/check-quick-validate-parity.py <quick_validate.py 路径> <用例 json 路径>
"""
import importlib.util
import json
import pathlib
import sys
import tempfile

# 验证器在另一个仓库（jm-agent-sandbox）里。Python 加载模块时默认会在它旁边写 __pycache__，
# 那个仓库的工作区就脏了（release.mjs deploy 要求工作区干净），所以这里一律不写字节码。
sys.dont_write_bytecode = True


def load_validator(path):
    spec = importlib.util.spec_from_file_location("quick_validate", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def content_of(case):
    if case.get("md") is not None:
        return case["md"]
    fill = case["fill"]
    return case["template"].replace("@@FILL@@", fill["char"] * fill["count"])


def main(validator_path, cases_path):
    validator = load_validator(validator_path)
    cases = json.loads(pathlib.Path(cases_path).read_text(encoding="utf-8"))
    mismatches = []
    for case in cases:
        with tempfile.TemporaryDirectory() as tmp:
            skill_dir = pathlib.Path(tmp)
            (skill_dir / "SKILL.md").write_text(content_of(case), encoding="utf-8")
            ok, message = validator.validate_skill(skill_dir)
        expected = case["quickValidate"] == "valid"
        if ok != expected:
            actual = "valid" if ok else "invalid"
            mismatches.append(f'{case["id"]}: 用例记的是 {case["quickValidate"]}，quick_validate.py 判的是 {actual}（{message}）')
    for line in mismatches:
        print(line)
    print(f"{len(cases) - len(mismatches)}/{len(cases)} 条与 quick_validate.py 一致")
    sys.exit(1 if mismatches else 0)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
