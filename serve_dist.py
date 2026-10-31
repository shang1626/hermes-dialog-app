#!/usr/bin/env python3
"""gx.99801886.xyz 的静态分发服务（取代 python3 -m http.server）。

为什么不用 http.server 了：
  · 它是单线程的——两个人同时下 APK，第二个就排队；带宽再大也用不上
  · 目录可以列出来——/、/update/、/drop-*/ 直接把文件清单摊给任何人看
  · 以 root 跑、无缓存头、无 nosniff

本文件保留「零依赖、标准库」这个优点，只补上面四条：
  · ThreadingHTTPServer：每个请求一个线程，并发下载互不阻塞
  · 目录请求一律 404（不提供索引，也没有 index.html 可给）
  · 任何以 . 开头的路径段直接 404；解析后 realpath 必须落在 DOCROOT 内
  · 加 X-Content-Type-Options / 分类 Cache-Control；APK 的 MIME 显式指定
  · 由 systemd 以无特权用户 gxstatic 运行（dist 用 ACL 只给读权限）

环境变量：GX_DOCROOT 默认 /root/hermes-app/dist，GX_PORT 默认 8644。
"""
from __future__ import annotations

import os
import posixpath
import sys
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

DOCROOT = os.path.realpath(os.environ.get("GX_DOCROOT", "/root/hermes-app/dist"))
PORT = int(os.environ.get("GX_PORT", "8644"))

CACHE_RULES = (
    (".apk", "public, max-age=86400"),
    (".json", "no-store"),
    (".txt", "no-store"),
)


def _cache_for(path: str) -> str:
    lowered = path.lower()
    for suffix, value in CACHE_RULES:
        if lowered.endswith(suffix):
            return value
    return "public, max-age=300"


class GxHandler(SimpleHTTPRequestHandler):
    server_version = "gx"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def _deny(self, code: int = HTTPStatus.NOT_FOUND) -> None:
        body = b"not found\n"
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _resolve(self) -> str | None:
        """把 URL 解析成 DOCROOT 内的真实文件路径；越界或可疑返回 None。"""
        raw = self.path.split("?", 1)[0].split("#", 1)[0]
        unquoted = self._unquote_path(raw)
        if unquoted is None:
            return None
        if any(seg.startswith(".") for seg in unquoted.split("/") if seg):
            return None
        target = os.path.realpath(os.path.join(DOCROOT, posixpath.normpath(unquoted).lstrip("/")))
        if target != DOCROOT and not target.startswith(DOCROOT + os.sep):
            return None
        if not os.path.isfile(target):
            return None  # 目录、缺失文件、设备文件一律不给
        return target

    def _unquote_path(self, raw: str) -> str | None:
        from urllib.parse import unquote

        try:
            value = unquote(raw, errors="strict")
        except (UnicodeDecodeError, ValueError):
            return None
        if "\x00" in value:
            return None
        return value

    def send_head(self):  # noqa: D401 - 覆盖 http.server 的目录/文件分发
        target = self._resolve()
        if target is None:
            return self._deny_static()
        try:
            handle = open(target, "rb")
        except OSError:
            return self._deny_static()
        try:
            stat = os.fstat(handle.fileno())
        except OSError:
            handle.close()
            return self._deny_static()

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", self.guess_type(target))
        self.send_header("Content-Length", str(stat.st_size))
        self.send_header("Cache-Control", _cache_for(target))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Accept-Ranges", "none")
        self.end_headers()
        return handle

    def _deny_static(self):
        self._deny()
        return None

    def guess_type(self, path) -> str:  # noqa: D401
        path = str(path)
        if path.lower().endswith(".apk"):
            return "application/vnd.android.package-archive"
        return super().guess_type(path)

    # 其他方法（PUT/DELETE/POST…）由基类回 501，这里只显式挡掉 TRACE/OPTIONS 噪音
    def do_OPTIONS(self):  # noqa: N802
        self._deny(HTTPStatus.METHOD_NOT_ALLOWED)

    def do_TRACE(self):  # noqa: N802
        self._deny(HTTPStatus.METHOD_NOT_ALLOWED)

    def log_message(self, format, *args) -> None:  # noqa: A002
        sys.stderr.write("%s - %s\n" % (self.address_string(), format % args))


class GxServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> int:
    if not os.path.isdir(DOCROOT):
        print(f"[失败] docroot 不存在：{DOCROOT}", file=sys.stderr)
        return 1
    handler = partial(GxHandler, directory=DOCROOT)
    with GxServer(("0.0.0.0", PORT), handler) as httpd:
        print(f"gx-static 就绪：{DOCROOT} → 0.0.0.0:{PORT}（目录不可列，多线程）", flush=True)
        httpd.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
