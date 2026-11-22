# -*- coding: utf-8 -*-
"""Build the on-box parental control APK without Gradle.

Uses javac + d8 + aapt2 + zipalign + apksigner from a locally downloaded
JDK / Android SDK. Everything is a standard-library call on the host.
"""
import os
import re
import sys
import glob
import shutil
import zipfile
import subprocess

BASE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(BASE, "build")
APKDIR = os.path.join(BASE, "apk")


def find(pattern):
    hits = glob.glob(os.path.join(BUILD, pattern))
    if not hits:
        raise RuntimeError("missing tool/file: " + pattern)
    return hits[0]


JDK = os.path.dirname(find("jdk/jdk-*/bin/javac.exe"))
BT = os.path.dirname(find("bt/android-*/aapt2.exe"))
ANDROID_JAR = find("platform/android-*/android.jar")

JAVAC = os.path.join(JDK, "javac.exe")
JAVA = os.path.join(JDK, "java.exe")
KEYTOOL = os.path.join(JDK, "keytool.exe")
AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
# The shipped .bat launchers pass -Djava.ext.dirs, which JDK 17 rejects.
# Calling the jars directly avoids that legacy flag entirely.
D8_JAR = os.path.join(BT, "lib", "d8.jar")
APKSIGNER_JAR = os.path.join(BT, "lib", "apksigner.jar")

CLASSES = os.path.join(APKDIR, "classes")
DEX = os.path.join(APKDIR, "dex")
BASE_APK = os.path.join(APKDIR, "base.apk")
ALIGNED = os.path.join(APKDIR, "aligned.apk")
FINAL = os.path.join(BASE, "TvBoxControl.apk")
KEYSTORE = os.path.join(APKDIR, "tvbox.keystore")
# Signing credentials are user-specific. Set SIGNING_PASS before building;
# a fresh keystore is generated on first build if none exists.
PASS = os.environ.get("SIGNING_PASS", "change-me-on-first-build")

env = dict(os.environ)
env["PATH"] = JDK + os.pathsep + env.get("PATH", "")
env["JAVA_HOME"] = os.path.dirname(JDK)


def run(args, label):
    print("=== %s ===" % label, flush=True)
    p = subprocess.run(args, cwd=APKDIR, env=env, capture_output=True, shell=False)
    enc = "gbk" if os.name == "nt" else "utf-8"
    out = ((p.stdout or b"").decode(enc, "replace")
           + (p.stderr or b"").decode(enc, "replace")).strip()
    if out:
        print(out[-2500:], flush=True)
    if p.returncode != 0:
        raise RuntimeError("%s failed (exit %d)" % (label, p.returncode))
    return out


def main():
    print("JDK        :", JDK)
    print("BUILD-TOOLS:", BT)
    print("android.jar:", ANDROID_JAR)

    # Reuse the output directories: javac/d8 overwrite files with the same
    # names. Avoiding a bulk delete keeps the build sandbox-friendly.
    for d in (CLASSES, DEX):
        os.makedirs(d, exist_ok=True)

    src = sorted(glob.glob(os.path.join(APKDIR, "src", "com", "tvbox", "parental", "*.java")))
    print("源文件:", [os.path.basename(x) for x in src], flush=True)

    # 1. javac against the Android framework
    run([JAVAC, "-source", "8", "-target", "8",
         "-bootclasspath", ANDROID_JAR,
         "-encoding", "UTF-8", "-d", CLASSES] + src, "javac")

    classfiles = glob.glob(os.path.join(CLASSES, "**", "*.class"), recursive=True)
    print("编译 class 数:", len(classfiles), flush=True)

    # 2. d8 -> classes.dex
    run([JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--lib", ANDROID_JAR,
         "--min-api", "26", "--output", DEX] + classfiles, "d8")
    dexfile = os.path.join(DEX, "classes.dex")
    if not os.path.isfile(dexfile):
        raise RuntimeError("classes.dex not produced")
    print("classes.dex:", os.path.getsize(dexfile), "bytes", flush=True)

    # 3. aapt2 link (binary manifest + assets)
    for f in (BASE_APK, ALIGNED, FINAL):
        if os.path.isfile(f):
            os.remove(f)
    # Read the version straight from the manifest so the two never drift.
    mf = open(os.path.join(APKDIR, "AndroidManifest.xml"), encoding="utf-8").read()
    vc = re.search(r'android:versionCode="(\d+)"', mf).group(1)
    vn = re.search(r'android:versionName="([^"]+)"', mf).group(1)
    run([AAPT2, "link", "-o", BASE_APK, "-I", ANDROID_JAR,
         "--manifest", "AndroidManifest.xml", "-A", "assets",
         "--min-sdk-version", "26", "--target-sdk-version", "29",
         "--version-code", vc, "--version-name", vn], "aapt2 link")
    print("版本: %s (code %s)" % (vn, vc), flush=True)

    # 4. inject classes.dex into the APK
    with zipfile.ZipFile(BASE_APK, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dexfile, "classes.dex")
    print("注入 classes.dex 完成", flush=True)

    # 5. zipalign
    run([ZIPALIGN, "-f", "4", BASE_APK, ALIGNED], "zipalign")

    # 6. signing key (created once) and apksigner
    if not os.path.isfile(KEYSTORE):
        run([KEYTOOL, "-genkeypair", "-keystore", KEYSTORE,
             "-storepass", PASS, "-keypass", PASS, "-alias", "tvbox",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-dname", "CN=TVBox Parental Control, O=Home, C=CN"], "keytool")
    run([JAVA, "-jar", APKSIGNER_JAR, "sign", "--ks", KEYSTORE,
         "--ks-pass", "pass:" + PASS, "--key-pass", "pass:" + PASS,
         "--out", FINAL, ALIGNED], "apksigner")
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", FINAL],
        "apksigner verify")

    print("\n构建完成:", FINAL)
    print("大小: %.2f MB" % (os.path.getsize(FINAL) / 1048576.0))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("BUILD FAILED:", e, file=sys.stderr)
        sys.exit(1)
