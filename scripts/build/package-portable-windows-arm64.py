#!/usr/bin/env python3
# =============================================================================
# Trunkline Windows ARM64 便携版组装脚本（可在任意构建机运行）
#
# 用法：python scripts/build/package-portable-windows-arm64.py --jre <win-aarch64-jre.zip>
#
# 产物：target/dist-win-arm64/Trunkline-0.1.0-win-arm64-portable.zip
#
# 【为什么不是 Trunkline.exe】
#   jpackage 不支持交叉编译：本机 x64 JDK 只能生成 x64 启动器（PE Machine 0x8664），
#   且 x64 启动器无法加载 ARM64 的 jvm.dll。故 ARM64 包采用
#   「内置 ARM64 JRE + 双击 启动.bat」形态——同样弹黑框、关窗即停，功能等价。
#
# 【与本机的验证边界】
#   在 x64 构建机上无法实际运行 ARM64 的 java.exe，只能静态校验：
#   PE Machine 必须是 0xAA64、jar md5 与源一致、结构完整、无 data/logs 污染。
# =============================================================================
import argparse
import hashlib
import os
import shutil
import struct
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read_project_version():
    """从 pom.xml 顶层 <version> 读取版本，避免打包脚本与 Maven 坐标脱钩。"""
    for child in ET.parse(ROOT / "pom.xml").getroot():
        if child.tag.rsplit("}", 1)[-1] == "version":
            return (child.text or "").strip()
    raise RuntimeError("pom.xml 缺少顶层 <version>")


APP_VERSION = read_project_version()
JAR_NAME = f"trunkline-{APP_VERSION}.jar"


def pe_machine(data: bytes):
    if data[:2] != b"MZ":
        return None
    e = struct.unpack_from("<I", data, 0x3C)[0]
    if e + 6 > len(data):
        return None
    return struct.unpack_from("<H", data, e + 4)[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jre", required=True, help="Windows aarch64 JRE 的 zip 路径")
    args = ap.parse_args()

    jre_zip = Path(args.jre)
    if not jre_zip.is_file():
        print(f"[ERROR] 找不到 JRE: {jre_zip}")
        sys.exit(1)

    jar_src = ROOT / "target" / JAR_NAME
    if not jar_src.is_file():
        print(f"[ERROR] 缺少 {jar_src}，请先构建 jar")
        sys.exit(1)
    jar_md5 = hashlib.md5(jar_src.read_bytes()).hexdigest()
    print(f"[OK] 应用 jar 就绪 md5={jar_md5}")

    out_dir = ROOT / "target" / "dist-win-arm64"
    build = out_dir / "build"
    stage = build / "Trunkline"
    if build.exists():
        shutil.rmtree(build)
    (stage / "app").mkdir(parents=True)

    # --- 解压 ARM64 JRE，定位含 bin/java.exe 的根 ---
    with zipfile.ZipFile(jre_zip) as z:
        jre_top = None
        for n in z.namelist():
            if n.endswith("/bin/java.exe"):
                jre_top = n[: -len("/bin/java.exe")]
                break
        if not jre_top:
            print("[ERROR] JRE 内未找到 bin/java.exe")
            sys.exit(1)
        mach = pe_machine(z.read(f"{jre_top}/bin/java.exe"))
        if mach != 0xAA64:
            print(f"[ERROR] JRE 的 java.exe 不是 ARM64（PE Machine=0x{mach:04X}），拒绝组装")
            sys.exit(1)
        print(f"[OK] 内置 runtime 校验通过 PE Machine=0x{mach:04X} (ARM64)")
        # 解压到临时，再把 jre_top 整体移动为 stage/runtime
        extract = build / "jre-extract"
        z.extractall(extract)
    shutil.move(str(extract / jre_top), str(stage / "runtime"))
    shutil.rmtree(extract)

    shutil.copy2(jar_src, stage / "app" / JAR_NAME)
    brand = ROOT / "logo" / "brand-mark.svg"
    if brand.is_file():
        shutil.copy2(brand, stage / "brand-mark.svg")

    # --- 启动.bat（CRLF 行尾，双击即用）---
    bat = (
        "@echo off\r\n"
        "cd /d \"%~dp0\"\r\n"
        "if not exist \"runtime\\bin\\java.exe\" (\r\n"
        "  echo 错误：未找到内置 runtime（runtime\\bin\\java.exe），请确认压缩包完整。\r\n"
        "  pause\r\n"
        "  exit /b 1\r\n"
        ")\r\n"
        "set SERVER__PORT=\r\n"
        "set SERVER__HOST=\r\n"
        "echo Trunkline 正在启动，稍后浏览器会自动打开 http://localhost:8080/admin/\r\n"
        "echo 按 Ctrl+C 或关闭本窗口即停止服务。\r\n"
        f'"runtime\\bin\\java.exe" -Dtrunkline.desktop.open-browser=true -jar "app\\{JAR_NAME}" --server.port=8080 %*\r\n'
        "pause\r\n"
    )
    (stage / "启动.bat").write_bytes(bat.encode("utf-8"))

    readme = (
        "Trunkline 智能路由模型网关 · Windows ARM64 便携版\n"
        "====================================================\n"
        "适用：Windows 10/11 on ARM（骁龙 X Elite/X Plus、Surface Pro X/9 等 ARM 设备）\n"
        "本包内置 ARM64 原生 JRE 21，无需安装 Java，也无需依赖 x64 模拟层。\n"
        "\n"
        "【三步开始使用】\n"
        "1. 解压到「文档」「下载」等你自己能修改的目录\n"
        "   （不要放 C:\\Program Files 这类系统目录，普通用户无写入权限）。\n"
        "2. 双击「启动.bat」。会弹出黑色命令行窗口，这是正常的——服务正在运行。\n"
        "   请不要关闭它；关掉 = 停止服务。\n"
        "3. 约 10 秒后浏览器自动打开 http://localhost:8080/admin/\n"
        "   若未自动打开，手动访问该地址。首次使用按初始化向导三步走\n"
        "   （默认本地 H2 数据库，直接下一步即可）。\n"
        "\n"
        "【日常使用】\n"
        "- 启动：双击 启动.bat    停止：关闭黑色命令行窗口\n"
        "- 数据：本文件夹下的 data 目录（请勿删除，删了会丢配置/密钥/历史）\n"
        "- 备份/换机：整个文件夹复制到新设备即可，数据跟着走\n"
        "\n"
        "【常见问题】\n"
        "Q：为什么是 .bat 而不是 .exe？\n"
        "   A：Windows 的 exe 启动器无法交叉编译出 ARM64 版本；.bat 调用 ARM64 原生\n"
        "      java.exe，功能与 x64 版的 Trunkline.exe 完全一致，同样双击即用。\n"
        "Q：双击后一闪而过？ A：多半放在系统保护目录，移到「文档/下载」再试。\n"
        "Q：浏览器没自动打开？ A：等约 10 秒手动访问 http://localhost:8080/admin/。\n"
        "Q：提示 8080 被占用？ A：关闭占用 8080 的程序，或改 启动.bat 里的 --server.port。\n"
        "\n"
        "【安全提示】\n"
        "- 本程序只在本机运行，数据不上传任何服务器。\n"
        "- 请勿把本文件夹或 data 目录上传到公开网络。\n"
        "- 首次初始化请设置足够复杂的管理员密码并妥善保管。\n"
    )
    (stage / "使用说明.txt").write_bytes(readme.encode("utf-8"))

    # --- 打 zip ---
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"Trunkline-{APP_VERSION}-win-arm64-portable.zip"
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in stage.rglob("*"):
            z.write(p, p.relative_to(build).as_posix())

    # --- 静态校验 ---
    print("--- 产物静态校验 ---")
    with zipfile.ZipFile(out) as z:
        names = z.namelist()
        jn = next(n for n in names if n.endswith(JAR_NAME))
        assert hashlib.md5(z.read(jn)).hexdigest() == jar_md5, "jar md5 不一致"
        jex = next(n for n in names if n.endswith("/bin/java.exe"))
        assert pe_machine(z.read(jex)) == 0xAA64, "java.exe 非 ARM64"
        bad = [n for n in names if "/data/" in n or "/logs/" in n]
        assert not bad, f"含测试污染: {bad[:3]}"
        assert any(n.endswith("启动.bat") for n in names)
        print(f"  jar md5 一致 / java.exe=ARM64 / 无 data,logs 污染 / 含 启动.bat")
    shutil.rmtree(build)
    size_mb = out.stat().st_size / 1048576
    print(f"[OK] 产物: {out} ({size_mb:.1f} MB)")
    print("[INFO] 注意：x64 构建机无法实跑 ARM64 runtime，仅完成静态校验。")


if __name__ == "__main__":
    main()
