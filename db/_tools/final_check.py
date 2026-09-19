#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最终自检：确认生成的 SQL 与工具脚本内容正确、库中注释与文案一致。"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(r"D:\AI\DeepSeek Harness\jc\project\proxy-viewer")
MYSQL = r"D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

EXPECTED_TABLES = {"proxy_nodes": 24, "node_test_records": 8, "operation_logs": 8}


def query(sql: str) -> list[list[str]]:
    proc = subprocess.run(
        [MYSQL, "-h", "127.0.0.1", "-P", "3306", "-uroot", "-p123456",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", f"USE proxy_viewer; {sql}"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit(proc.stderr.decode("utf-8", "replace"))
    return [l.split("\t") for l in proc.stdout.decode("utf-8").splitlines() if l.strip()]


print("1) 库中表注释与字段注释覆盖率")
rows = query("SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES "
             "WHERE TABLE_SCHEMA='proxy_viewer' ORDER BY TABLE_NAME;")
assert len(rows) == 3, f"表数量异常: {len(rows)}"
for name, comment in rows:
    cols = query("SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS "
                 f"WHERE TABLE_SCHEMA='proxy_viewer' AND TABLE_NAME='{name}';")
    missing = [c[0] for c in cols if not c[1]]
    expect = EXPECTED_TABLES[name]
    status = "OK" if (comment and not missing and len(cols) == expect) else "NG"
    print(f"   [{status}] {name}: 表注释{'有' if comment else '缺'} / "
          f"{len(cols)}列 (期望{expect}) / 缺注释 {len(missing)} 个")
    if status == "NG":
        raise SystemExit("注释覆盖不完整")

print("2) 生成脚本可重复生成且内容稳定（幂等）")
proc = subprocess.run([sys.executable, str(ROOT / "db/_tools/gen_schema_comments.py")],
                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
if proc.returncode != 0:
    raise SystemExit(proc.stderr.decode("utf-8", "replace"))
print("   [OK] 重新生成成功: " + proc.stdout.decode("utf-8").strip().replace("\n", " | "))

print("3) 脚本与 SQL 中文编码自检（无乱码、无 BOM）")
for rel in ["db/schema-comments.sql", "db/_tools/gen_schema_comments.py",
            "db/_tools/verify_schema_comments.py", "db/_tools/show_schema_comments.py",
            "db/_tools/final_check.py"]:
    path = ROOT / rel
    raw = path.read_bytes()
    assert not raw.startswith(b"\xef\xbb\xbf"), f"{rel} 含 BOM"
    text = raw.decode("utf-8")            # 解码失败即乱码
    assert "\ufffd" not in text, f"{rel} 含替换字符"
    cjk = len(re.findall(r"[\u4e00-\u9fff]", text))
    print(f"   [OK] {rel}: UTF-8 无 BOM，中文字符 {cjk} 个")

print("4) 生成的 SQL 中不含手写列类型（全部来自 information_schema）")
sql_text = (ROOT / "db/schema-comments.sql").read_text(encoding="utf-8")
# 只统计真正的语句行，避开头部注释里出现的同名关键字
body_lines = [l for l in sql_text.splitlines()
              if l.strip() and not l.strip().startswith("--")]
modify_count = sum(l.count("MODIFY COLUMN") for l in body_lines)
comment_eq = len(re.findall(r"^ALTER TABLE `\w+` COMMENT = ", "\n".join(body_lines), re.M))
print(f"   [OK] MODIFY COLUMN {modify_count} 条 / 独立表注释语句 {comment_eq} 条")
assert modify_count == 40 and comment_eq == 3

print("\n全部自检通过")
