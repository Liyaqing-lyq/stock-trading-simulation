"""从已封卷的世界导出逐户现金、主体估值及开局至截止收益。

--build 指向使用 mvnw test 构建的源码目录。现支持 web-ten-company-autonomy-v24 正式档，
以及实测历史档 web-ten-company-autonomy-v2、web-owner-bank-valuation-v1、web-unpriced-reference-v1。
从首帧读取实际身份，其他档案会明确报错。原件只读，临时副本独立重开。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from xml.etree import ElementTree

JAVA_SOURCE = Path(__file__).with_name("OwnerPerformanceReport.java")


def build_runtime(build):
    build = Path(build).resolve(strict=True)
    for file in sorted((build / "target/surefire-reports").glob("TEST-*.xml")):
        suite = ElementTree.parse(file).getroot()
        if int(suite.get("tests")) <= int(suite.get("skipped", 0)) or any(int(suite.get(k)) for k in ("errors", "failures")):
            continue
        props = {item.get("name"): item.get("value") for item in suite.find("properties")}
        cp = props["java.class.path"]
        expected = [(build / "target/test-classes").resolve(), (build / "target/classes").resolve()]
        if [Path(p).resolve() for p in cp.split(os.pathsep)[:2]] != expected:
            raise ValueError("测试类路径不属于指定构建")
        java = Path(props["java.home"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        return java, cp
    raise ValueError("指定构建没有通过的完整 Surefire 测试记录")


def export(journal, build, scratch=None):
    journal = Path(journal).resolve(strict=True)
    java, cp = build_runtime(build)
    with tempfile.TemporaryDirectory(dir=scratch) as temporary:
        work = Path(temporary)
        sealed_copy = work / "cut.journal"
        digest = hashlib.sha256()
        with journal.open("rb") as source, sealed_copy.open("xb") as target:
            while chunk := source.read(1024 * 1024):
                target.write(chunk)
                digest.update(chunk)
        output = work / "report.json"
        env = {**os.environ, "TEMP": str(work), "TMP": str(work), "TMPDIR": str(work)}
        subprocess.run([str(java), "-Xmx2048m", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(work),
                        "-cp", cp, str(JAVA_SOURCE), str(sealed_copy), str(work / "opening.journal"), str(output)],
                       cwd=work, env=env, check=True)
        with sealed_copy.open("rb") as reopened:
            after = hashlib.sha256()
            while chunk := reopened.read(1024 * 1024):
                after.update(chunk)
        if after.digest() != digest.digest():
            raise ValueError("冻结世界严开后副本字节发生变化")
        result = json.loads(output.read_bytes())
    result["journalSha256"] = digest.hexdigest()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("journal", type=Path)
    parser.add_argument("--build", type=Path, required=True, help="已通过 Maven 测试的冻结构建目录")
    parser.add_argument("--scratch", type=Path, help="临时副本所在目录")
    parser.add_argument("--output", type=Path, help="新建的 JSON 路径，默认写到标准输出")
    args = parser.parse_args()
    result = export(args.journal, args.build, args.scratch)
    if args.output is None:
        print(json.dumps(result, ensure_ascii=False))
    else:
        with args.output.open("x", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")


if __name__ == "__main__":
    main()
