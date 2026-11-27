import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TINYASR_DIR = os.path.join(ROOT, "native", "tinyasr")
JNILIBS_DIR = os.path.join(ROOT, "app", "src", "main", "cpp", "prebuilt")

NDK_BIN = r"D:\AndroidToolchain\android-sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin"
CARGO_BIN = r"D:\AndroidToolchain\cargo\bin"

TARGETS = [
    ("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android26-clang.cmd"),
    ("armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi26-clang.cmd"),
    ("x86_64-linux-android", "x86_64", "x86_64-linux-android26-clang.cmd"),
]

def main():
    env = os.environ.copy()
    env["RUSTUP_HOME"] = r"D:\AndroidToolchain\rustup"
    env["CARGO_HOME"] = r"D:\AndroidToolchain\cargo"
    env["PATH"] = f"{CARGO_BIN};{NDK_BIN};" + env.get("PATH", "")

    ar_path = os.path.join(NDK_BIN, "llvm-ar.exe")
    cargo_exe = os.path.join(CARGO_BIN, "cargo.exe")

    for rust_target, abi, linker_cmd in TARGETS:
        linker_path = os.path.join(NDK_BIN, linker_cmd)
        target_env = env.copy()
        env_key = rust_target.upper().replace("-", "_")
        target_env[f"CARGO_TARGET_{env_key}_LINKER"] = linker_path
        target_env[f"CARGO_TARGET_{env_key}_AR"] = ar_path
        target_env["CC_" + rust_target.replace("-", "_")] = linker_path
        target_env["AR_" + rust_target.replace("-", "_")] = ar_path

        print(f"\n=== Building tinyasr for {rust_target} ({abi}) ===")
        cmd = [
            cargo_exe,
            "build",
            "--release",
            "--features",
            "c-api",
            "--target",
            rust_target,
        ]
        subprocess.run(cmd, cwd=TINYASR_DIR, env=target_env, check=True)

        src_lib = os.path.join(TINYASR_DIR, "target", rust_target, "release", "libtinyasr.a")
        dst_dir = os.path.join(JNILIBS_DIR, abi)
        os.makedirs(dst_dir, exist_ok=True)
        dst_lib = os.path.join(dst_dir, "libtinyasr.a")
        shutil.copy2(src_lib, dst_lib)
        print(f"Copied {dst_lib} ({os.path.getsize(dst_lib)} bytes)")

if __name__ == "__main__":
    main()
