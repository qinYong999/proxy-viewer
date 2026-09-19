#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""以 UTF-8 打印 proxy_viewer 库的表注释 / 字段注释，用于人工核对。

Windows 控制台默认 GBK，直接 print 中文/符号会乱码或抛 UnicodeEncodeError，
这里统一把 stdout 切到 UTF-8，并显式使用 mysql 的 utf8mb4 连接。
"""

from __future__ import annotations

import argparse
import subprocess
import sys

DEFAULT_MYSQL = r"D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"


def query(mysql_exe: str, database: str, sql: str) -> list[list[str]]:
    cmd = [mysql_exe, "-h", "127.0.0.1", "-P", "3306", "-uroot", "-p123456",
           "--default-character-set=utf8mb4", "-N", "-B", "-e", f"USE `{database}`; {sql}"]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("mysql 查询失败:\n" + proc.stderr.decode("utf-8", "replace"))
    return [line.split("\t") for line in proc.stdout.decode("utf-8").splitlines() if line.strip()]


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="proxy_viewer")
    parser.add_argument("--mysql", default=DEFAULT_MYSQL)
    parser.add_argument("--like", default="", help="只看表名匹配该 LIKE 模式的表（试跑核对用）")
    args = parser.parse_args()

    where = "AND TABLE_NAME LIKE '%s'" % args.like.replace("%", "%%") if args.like else ""

    tables = query(args.mysql, args.database,
                   "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES "
                   f"WHERE TABLE_SCHEMA='{args.database}' {where} ORDER BY TABLE_NAME;")

    grand_total = 0
    for name, comment in tables:
        cols = query(args.mysql, args.database,
                     "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_COMMENT,'') "
                     "FROM information_schema.COLUMNS "
                     f"WHERE TABLE_SCHEMA='{args.database}' AND TABLE_NAME='{name}' "
                     "ORDER BY ORDINAL_POSITION;")
        missing = [c[0] for c in cols if not c[3]]
        grand_total += len(cols)
        print("=" * 78)
        print(f"表 {name}  ({len(cols)} 列)")
        print(f"  表注释: {comment or '【缺失】'}")
        if missing:
            print(f"  !! 缺注释的列: {', '.join(missing)}")
        print("-" * 78)
        for cname, ctype, nullable, ccomment in cols:
            null_mark = "NULL" if nullable == "YES" else "NOT NULL"
            print(f"  {cname:<22} {ctype:<18} {null_mark:<9} {ccomment or '【缺失】'}")
        print()
    print(f"共 {len(tables)} 张表、{grand_total} 个字段")
    return 0


if __name__ == "__main__":
    sys.exit(main())
