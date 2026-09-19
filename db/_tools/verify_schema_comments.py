#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在克隆表上试跑 schema-comments.sql，验证列定义不发生任何改变（只有注释被追加）。

做法
----
1. CREATE TABLE ... LIKE 克隆三张表为 <表名>_cmt_test（LIKE 不复制数据，秒级完成）；
2. 把生成好的 ALTER 语句里的表名重定向到克隆表后执行；
3. 逐列比对克隆表与原表在「列顺序 + 类型 + 字符集 + 排序规则 + NULL + 默认值 + EXTRA」上是否完全一致；
4. 删除克隆表，输出结论。

用法：
    python verify_schema_comments.py            # 完整试跑
    python verify_schema_comments.py --keep     # 保留克隆表以便人工查看
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Windows 控制台默认 GBK，统一切到 UTF-8，避免中文输出乱码或抛 UnicodeEncodeError
sys.stdout.reconfigure(encoding="utf-8")

DEFAULT_MYSQL = r"D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
TABLES = ["proxy_nodes", "node_test_records", "operation_logs"]
SUFFIX = "_cmt_test"

COLUMN_FIELDS = ["COLUMN_NAME", "COLUMN_TYPE", "IS_NULLABLE", "COLUMN_DEFAULT",
                 "EXTRA", "CHARACTER_SET_NAME", "COLLATION_NAME", "COLUMN_KEY"]


def run_mysql(mysql_exe: str, database: str, sql: str) -> str:
    cmd = [mysql_exe, "-h", "127.0.0.1", "-P", "3306", "-uroot", "-p123456",
           "--default-character-set=utf8mb4", "-N", "-B", "-e", f"USE `{database}`; {sql}"]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stderr = proc.stderr.decode("utf-8", "replace")
    if proc.returncode != 0:
        raise SystemExit(f"mysql 执行失败:\n{stderr}\nSQL: {sql}")
    return proc.stdout.decode("utf-8")


def snapshot(mysql_exe: str, database: str, table: str) -> list[tuple]:
    cols = ", ".join(f"IFNULL({f},'')" for f in COLUMN_FIELDS)
    sql = (f"SELECT {cols} FROM information_schema.COLUMNS "
           f"WHERE TABLE_SCHEMA='{database}' AND TABLE_NAME='{table}' "
           f"ORDER BY ORDINAL_POSITION;")
    cmd = [mysql_exe, "-h", "127.0.0.1", "-P", "3306", "-uroot", "-p123456",
           "--default-character-set=utf8mb4", "-N", "-B", "-e", sql]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("读取 information_schema 失败:\n" + proc.stderr.decode("utf-8", "replace"))
    rows = []
    for line in proc.stdout.decode("utf-8").splitlines():
        if line.strip():
            rows.append(tuple(line.split("\t")))
    return rows


def table_comment(mysql_exe: str, database: str, table: str) -> str:
    sql = (f"SELECT IFNULL(TABLE_COMMENT,'') FROM information_schema.TABLES "
           f"WHERE TABLE_SCHEMA='{database}' AND TABLE_NAME='{table}';")
    cmd = [mysql_exe, "-h", "127.0.0.1", "-P", "3306", "-uroot", "-p123456",
           "--default-character-set=utf8mb4", "-N", "-B", "-e", sql]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return proc.stdout.decode("utf-8").strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="proxy_viewer")
    parser.add_argument("--mysql", default=DEFAULT_MYSQL)
    parser.add_argument("--sql", default=str(Path(__file__).resolve().parent.parent / "schema-comments.sql"))
    parser.add_argument("--keep", action="store_true", help="保留克隆表")
    args = parser.parse_args()

    mysql_exe, db = args.mysql, args.database
    script = Path(args.sql).read_text(encoding="utf-8")

    # 1) 克隆
    for t in TABLES:
        run_mysql(mysql_exe, db, f"DROP TABLE IF EXISTS `{t}{SUFFIX}`; CREATE TABLE `{t}{SUFFIX}` LIKE `{t}`;")
    print(f"已克隆 {len(TABLES)} 张表为 *{SUFFIX}")

    # 2) 重定向表名后执行生成的 ALTER 语句
    redirected = script.replace("USE `proxy_viewer`;", "")
    for t in TABLES:
        redirected = re.sub(rf"ALTER TABLE `{t}`", f"ALTER TABLE `{t}{SUFFIX}`", redirected)
    # 注释文案里可能含分号，不能按 ';' 粗暴切分；
    # 这里以「行首的语句起始关键字」为界把脚本拆成独立语句。
    body = "\n".join(
        line for line in redirected.splitlines()
        if line.strip() and not line.strip().startswith("--")
    )
    statements = [s.strip() for s in re.split(r"(?m)^(?=\s*(?:ALTER|USE|DROP|CREATE)\b)", body) if s.strip()]
    if not statements:
        raise SystemExit("未从 SQL 文件中解析出任何语句，请检查生成结果")
    for stmt in statements:
        run_mysql(mysql_exe, db, stmt)
    print(f"已在克隆表上执行 {len(statements)} 条 ALTER 语句")

    # 3) 比对列定义
    ok = True
    for t in TABLES:
        before = snapshot(mysql_exe, db, t)
        after = snapshot(mysql_exe, db, f"{t}{SUFFIX}")
        if before != after:
            ok = False
            print(f"[NG] {t}: 列定义发生变化")
            for i, (b, a) in enumerate(zip(before, after)):
                if b != a:
                    print(f"    第 {i + 1} 列:\n      原: {b}\n      新: {a}")
            if len(before) != len(after):
                print(f"    列数不同: 原 {len(before)} / 新 {len(after)}")
        else:
            print(f"[OK] {t}: {len(before)} 列的 列名/类型/字符集/排序规则/NULL/默认值/EXTRA/键 完全一致")
        tc = table_comment(mysql_exe, db, f"{t}{SUFFIX}")
        if not tc:
            ok = False
            print(f"[NG] {t}: 表注释未写入")
        else:
            print(f"    表注释已写入: {tc[:40]}...")

    # 4) 清理
    if args.keep:
        print(f"已保留克隆表（*{SUFFIX}）")
    else:
        for t in TABLES:
            run_mysql(mysql_exe, db, f"DROP TABLE IF EXISTS `{t}{SUFFIX}`;")
        print("已清理克隆表")

    print("\n结论: " + ("全部通过，可以安全应用到真实表" if ok else "存在差异，请勿应用到真实表"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
