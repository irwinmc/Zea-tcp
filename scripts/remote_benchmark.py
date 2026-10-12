#!/usr/bin/env python3
"""Compare one isolated echo server at a time, preserving a report after every workload."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time

from test_report import save_report


def identities():
    """Capture PID and start time for existing Java, MySQL and Redis processes."""
    result = {}
    for path in Path("/proc").iterdir():
        if not path.name.isdigit():
            continue
        try:
            name = (path / "comm").read_text().strip()
            if name in ("java", "mysqld", "redis-server"):
                result[path.name] = {"name": name, "start_ticks": (path / "stat").read_text().rsplit(")", 1)[1].split()[19]}
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    return result


def available_memory():
    """Read host memory headroom without altering system state."""
    return int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines()
                    if line.startswith("MemAvailable:"))) * 1024


def cpu_ticks():
    """Read aggregate host busy and idle counters."""
    values = list(map(int, Path("/proc/stat").read_text().splitlines()[0].split()[1:9]))
    return sum(values), values[3] + values[4]


class Monitor:
    """Monitor only task-owned transient units and enforce host headroom."""

    def __init__(self, root, prefix, server_period_millis=100):
        self.root = root
        self.prefix = prefix
        self.server_period_millis = server_period_millis
        self.units = {}
        self.before = identities()
        self.minimum_available = available_memory()
        self.last_cpu = cpu_ticks()
        self.busy_samples = 0
        self.peak_busy = 0
        self.started = time.monotonic()

    def tick(self):
        """Sample owned units and stop progress when the host needs its resources."""
        available = available_memory()
        self.minimum_available = min(self.minimum_available, available)
        current = cpu_ticks()
        total = current[0] - self.last_cpu[0]
        if total >= os.cpu_count() * os.sysconf("SC_CLK_TCK") * 0.1:
            busy = 100 * (1 - (current[1] - self.last_cpu[1]) / total)
            self.peak_busy = max(self.peak_busy, busy)
            self.busy_samples = self.busy_samples + 1 if busy > 35 else 0
        self.last_cpu = current
        if available < 8 * 1024 ** 3 or self.busy_samples >= 8 or time.monotonic() - self.started > 80:
            raise RuntimeError("Safety stop: host headroom or case deadline reached")
        for unit, record in self.units.items():
            group = Path("/sys/fs/cgroup/system.slice") / unit
            try:
                record["cpu_max"] = (group / "cpu.max").read_text().strip()
                record["memory_max"] = int((group / "memory.max").read_text())
                allowance, period = map(int, record["cpu_max"].split())
                if (allowance / period > record["quota"] / 100 + 0.001
                        or period != record["cpu_period_millis"] * 1000
                        or record["memory_max"] != record["memory_mib"] * 1024 ** 2):
                    raise RuntimeError("Owned unit limits did not match the requested envelope")
                stats = {key: int(value) for key, value in (line.split() for line in (group / "cpu.stat").read_text().splitlines())}
                for key in ("usage_usec", "throttled_usec"):
                    record[key] = max(record.get(key, 0), stats.get(key, 0))
                record["memory_peak"] = max(record.get("memory_peak", 0), int((group / "memory.peak").read_text()))
                for pid in (group / "cgroup.procs").read_text().split():
                    try:
                        rss = next((int(line.split()[1]) * 1024 for line in Path("/proc", pid, "status").read_text().splitlines()
                                    if line.startswith("VmHWM:")), 0)
                        record["rss_peak"] = max(record.get("rss_peak", 0), rss)
                    except (FileNotFoundError, ProcessLookupError):
                        pass
            except FileNotFoundError:
                pass

    def start(self, java, index, role, quota, artifact, arguments, profile=False):
        """Start one bounded unit in the unique test directory and return its handles."""
        unit = f"{self.prefix}-{index}-{role}.service"
        memory = 768 if role == "server" else 512
        period = self.server_period_millis if role == "server" else 100
        self.units[unit] = {"quota": quota, "memory_mib": memory, "role": role, "cpu_period_millis": period}
        command = ["systemd-run", "--quiet", "--wait", "--pipe", "--collect", "--unit=" + unit,
                   "--property=CPUQuota=" + str(quota) + "%", "--property=CPUQuotaPeriodSec=" + str(period) + "ms",
                   "--property=CPUWeight=1", "--property=Nice=19",
                   "--property=MemoryMax=" + str(memory) + "M", "--property=MemoryHigh=" + str(memory - 128) + "M",
                   "--property=MemorySwapMax=0", "--property=TasksMax=192", "--property=RuntimeMaxSec=70",
                   "--property=OOMPolicy=stop", "--working-directory=" + str(self.root), java,
                   "-Xms64m", "-Xmx256m", "-Xss512k", "-XX:MaxDirectMemorySize=128m", "-XX:MaxMetaspaceSize=128m",
                   "-XX:ActiveProcessorCount=1", "-Dlogback.configurationFile=" + str(self.root / "quiet.xml")]
        if profile:
            command.append("-XX:StartFlightRecording=settings=profile,maxsize=16m,dumponexit=true,filename="
                           + str(self.root / (str(index) + ".jfr")))
        command += ["-cp", str(self.root / "classes") + ":" + str(self.root / (artifact + ".jar")) + ":" + str(self.root / "lib/*"),
                    "com.akakata.benchmark.NetworkLoadTest", *arguments]
        log_path = self.root / f"{index}-{role}.log"
        log = log_path.open("w")
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        return process, log, log_path

    def wait(self, process, seconds):
        """Wait while monitoring the host, with a bounded process deadline."""
        deadline = time.monotonic() + seconds
        while process.poll() is None:
            self.tick()
            if time.monotonic() >= deadline:
                raise RuntimeError("Owned process completion deadline exceeded")
            time.sleep(0.25)
        return process.returncode

    def marker(self, process, path, prefix, previous=0):
        """Wait for an ordered server readiness or snapshot marker."""
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            self.tick()
            lines = [line[len(prefix):] for line in path.read_text(errors="replace").splitlines() if line.startswith(prefix)]
            if len(lines) > previous:
                return json.loads(lines[-1])
            if process.poll() is not None:
                raise RuntimeError("Echo server exited before " + prefix + ": " + path.read_text(errors="replace")[-2000:])
            time.sleep(0.25)
        raise RuntimeError("Echo server marker deadline exceeded: " + prefix)

    def close(self):
        """Stop only units created by this case, never use a process-name kill."""
        for unit in self.units:
            subprocess.run(["systemctl", "stop", unit], capture_output=True, timeout=12)


def parse_result(path):
    """Extract workload evidence even when the workload fails."""
    result = {}
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("RESULT "):
            for item in line.split()[1:]:
                key, value = item.split("=", 1)
                try:
                    value = float(value) if "." in value else int(value)
                except ValueError:
                    pass
                result[key] = value
    return result


def run_case(root, java, prefix, index, specification, manifest, allow_baseline_burst_failure=True):
    """Run one server, a separate warmup client, and a separate measured client."""
    name, artifact, transport, quota, threads, clients, messages, payload, window, slow, profile, *settings = specification
    period_millis = settings[0] if settings else 100
    warmup_messages = settings[1] if len(settings) > 1 else (
        128 if payload == 128 or not allow_baseline_burst_failure else 8)
    monitor = Monitor(root, prefix, period_millis)
    handles = []
    record = {"name": name, "artifact": artifact, "artifact_sha256": manifest[artifact],
              "transport": transport, "server_cpu_quota_percent_of_one_core": quota, "client_cpu_quota_percent": 100,
              "io_workers": threads, "session_shards": threads, "workload": {"clients": clients, "messages": messages,
              "payload_bytes": payload, "window": window, "slow_read_millis": slow},
              "server_cpu_quota_period_millis": period_millis, "warmup_messages_per_client": warmup_messages,
              "status": "failed"}
    print("START " + name, flush=True)
    try:
        monitor.tick()
        server, log, path = monitor.start(java, index, "server", quota, artifact,
                                        ["--server-only", "--transport=" + transport, "--config=benchmark" + str(threads) + ".properties"], profile)
        handles.append((server, log))
        ready = monitor.marker(server, path, "READY ")
        base = ["--transport=" + transport, "--port=" + str(ready["port"]), "--clients=" + str(clients),
                "--payload-bytes=" + str(payload), "--window=" + str(window), "--warmup-messages=0", "--timeout-seconds=30"]
        warmup, log, warm_path = monitor.start(java, index, "warmup", 100, "candidate", base + ["--messages=" + str(warmup_messages)])
        handles.append((warmup, log))
        record["warmup_exit_code"] = monitor.wait(warmup, 34)
        record["warmup"] = parse_result(warm_path)
        if record["warmup_exit_code"] != 0:
            raise RuntimeError("Separate warmup workload failed")
        server.stdin.write("snapshot\n")
        server.stdin.flush()
        record["before"] = monitor.marker(server, path, "SERVER_METRICS ")
        started = time.monotonic()
        client, log, client_path = monitor.start(java, index, "client", 100, "candidate",
                                                base + ["--messages=" + str(messages), "--slow-read-millis=" + str(slow)])
        handles.append((client, log))
        record["client_exit_code"] = monitor.wait(client, 34)
        record["measurement_interval_seconds"] = round(time.monotonic() - started, 3)
        server.stdin.write("snapshot\n")
        server.stdin.flush()
        record["after"] = monitor.marker(server, path, "SERVER_METRICS ", 1)
        record["result"] = parse_result(client_path)
        record["server_measurement_cpu_seconds"] = (record["after"]["process_cpu_nanos"] - record["before"]["process_cpu_nanos"]) / 1e9
        record["server_measurement_gc_count"] = record["after"]["gc_count"] - record["before"]["gc_count"]
        record["server_measurement_gc_millis"] = record["after"]["gc_millis"] - record["before"]["gc_millis"]
        record["server_measurement_live_thread_allocated_bytes"] = (record["after"]["live_thread_allocated_bytes"]
                                                                  - record["before"]["live_thread_allocated_bytes"])
        if record["result"].get("completed", 0):
            record["server_live_thread_allocated_bytes_per_reply"] = round(
                record["server_measurement_live_thread_allocated_bytes"] / record["result"]["completed"], 1)
        server.stdin.write("stop\n")
        server.stdin.flush()
        record["server_exit_code"] = monitor.wait(server, 12)
        record["cleanup_ok"] = "CLEANUP_OK" in path.read_text(errors="replace")
        success = record["client_exit_code"] == 0 and record["server_exit_code"] == 0 and record["cleanup_ok"]
        record["status"] = "passed" if success else "failed"
        if allow_baseline_burst_failure and artifact == "baseline" and payload == 4096:
            record["expected_failure_reproduced"] = not success and record["result"].get("failed", 0) > 0
    except Exception as error:
        record["error"] = str(error)
    finally:
        monitor.close()
        for process, log in handles:
            if process.poll() is None:
                process.wait(timeout=12)
            log.close()
        record["units"] = monitor.units
        record["minimum_host_available_mib"] = round(monitor.minimum_available / 1024 ** 2, 1)
        record["peak_host_busy_percent"] = round(monitor.peak_busy, 1)
        record["existing_processes_preserved"] = all(identities().get(pid) == identity for pid, identity in monitor.before.items())
        save_report(root / "reports", name, record)
        for role in ("server", "warmup", "client"):
            source = root / f"{index}-{role}.log"
            if source.exists():
                shutil.copy2(source, root / "reports" / (name + "-" + role + ".log"))
        print("REPORT " + json.dumps(record), flush=True)
    return record


def worker(root, java):
    """Run a small bounded comparison matrix on Linux."""
    manifest = json.loads((root / "manifest.json").read_text())
    for name, digest in manifest.items():
        if hashlib.sha256((root / (name + ".jar")).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Artifact checksum mismatch: " + name)
    prefix = "zea-opt-" + root.name.rsplit(".", 1)[-1].lower()
    matrix = [
        ("04-baseline-tcp-burst", "baseline", "tcp", 50, 1, 1, 256, 4096, 64, 0, False),
        ("05-candidate-tcp-burst", "candidate", "tcp", 50, 1, 1, 512, 4096, 64, 0, False),
        ("06-candidate-websocket-burst", "candidate", "websocket", 50, 1, 1, 512, 4096, 64, 0, False),
        ("07-candidate-slow-reader", "candidate", "tcp", 50, 1, 1, 256, 4096, 64, 2, False),
        ("08-baseline-tcp-cpu50", "baseline", "tcp", 50, 1, 64, 256, 128, 4, 0, False),
        ("09-candidate-tcp-cpu50-threads1-jfr", "candidate", "tcp", 50, 1, 64, 256, 128, 4, 0, True),
        ("09b-candidate-tcp-cpu50-threads1", "candidate", "tcp", 50, 1, 64, 256, 128, 4, 0, False),
        ("10-candidate-tcp-cpu50-threads2", "candidate", "tcp", 50, 2, 64, 256, 128, 4, 0, False),
        ("11-candidate-tcp-cpu33-threads1", "candidate", "tcp", 33, 1, 64, 256, 128, 4, 0, False),
        ("12-candidate-tcp-cpu33-threads2", "candidate", "tcp", 33, 2, 64, 256, 128, 4, 0, False),
        ("13-candidate-websocket-cpu33", "candidate", "websocket", 33, 1, 64, 256, 128, 4, 0, False),
        ("16-candidate-tcp-cpu33-period20", "candidate", "tcp", 33, 1, 64, 256, 128, 4, 0, False, 20),
    ]
    custom_matrix = root / "matrix.json"
    if custom_matrix.exists():
        matrix = json.loads(custom_matrix.read_text())
        for row in matrix:
            if (len(row) not in (11, 12, 13) or row[1] not in ("baseline", "candidate")
                    or row[2] not in ("tcp", "websocket") or not 0 < row[3] <= 50
                    or row[4] not in (1, 2) or not 0 < row[5] <= 128
                    or row[5] * row[6] > 32768 or not 0 <= row[7] <= 60000
                    or not 0 < row[8] <= 1024 or Path(row[0]).name != row[0]
                    or (len(row) == 13 and not 0 <= row[12] <= 512)):
                raise ValueError("Custom workload exceeds the isolated test envelope")
    baseline = identities()
    report = {"artifacts": manifest, "host_cpu_count": os.cpu_count(), "baseline_existing_processes": baseline, "cases": []}
    try:
        for index, specification in enumerate(matrix):
            case = run_case(root, java, prefix, index, specification, manifest,
                            allow_baseline_burst_failure=not custom_matrix.exists())
            report["cases"].append(case)
            save_report(root / "reports", "remote-summary", report)
            if not case["existing_processes_preserved"] or (case["status"] != "passed" and not case.get("expected_failure_reproduced")):
                break
    finally:
        report["existing_processes_preserved"] = all(identities().get(pid) == identity for pid, identity in baseline.items())
        report["memory_available_after_mib"] = round(available_memory() / 1024 ** 2, 1)
        save_report(root / "reports", "remote-summary", report)
    print("FINAL " + json.dumps({"cases": len(report["cases"]), "existing_processes_preserved": report["existing_processes_preserved"]}), flush=True)


def ssh(config_path, command, data=None, stream=None, timeout=60):
    """Use native SSH askpass without placing credentials in argv, environment or reports."""
    config = json.loads(config_path.read_text())
    with tempfile.TemporaryDirectory(prefix="zea-ssh-") as directory:
        askpass = Path(directory) / "askpass"
        askpass.write_text("#!/usr/bin/env python3\nimport json,sys\nfrom pathlib import Path\n"
                           + "sys.stdout.write(json.loads(Path(" + repr(str(config_path.resolve())) + ").read_text())[\"password\"]+\"\\n\")\n")
        askpass.chmod(0o700)
        environment = dict(os.environ, SSH_ASKPASS=str(askpass), SSH_ASKPASS_REQUIRE="force", DISPLAY="zea-ssh")
        args = ["ssh", "-T", "-p", str(config.get("port", 22)), "-l", config["user"],
                "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=10",
                "-o", "ServerAliveCountMax=2", "-o", "PreferredAuthentications=password,keyboard-interactive",
                "-o", "PubkeyAuthentication=no", "-o", "NumberOfPasswordPrompts=1", config["host"], command]
        if stream is not None:
            process = subprocess.Popen(args, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            with stream.open("w") as log:
                for line in process.stdout:
                    safe = line.replace(config["password"], "[redacted]")
                    log.write(safe)
                    log.flush()
                    if safe.startswith("REPORT "):
                        record = json.loads(safe[7:])
                        save_report(stream.parent, record["name"], record)
                        print(json.dumps({key: record.get(key) for key in ("name", "status", "result", "server_measurement_cpu_seconds", "existing_processes_preserved")}), flush=True)
                    elif safe.startswith(("START ", "FINAL ", "Traceback")):
                        print(safe, end="", flush=True)
            if process.wait() != 0:
                raise RuntimeError("Remote runner exited unsuccessfully; see the captured log")
            return b""
        result = subprocess.run(args, input=data, env=environment, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace").replace(config["password"], "[redacted]"))
        return result.stdout


def main():
    """Stage public artifacts, execute the isolated worker, collect reports and clean up."""
    if sys.argv[1:2] == ["--worker"]:
        worker(Path(__file__).resolve().parent, sys.argv[2])
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("remote-test.local.json"))
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--baseline-jar", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--candidate-jar", type=Path, default=Path("target/zea-tcp-0.8.3.jar"))
    parser.add_argument("--matrix", type=Path, help="Optional bounded workload matrix; every listed workload must pass")
    parser.add_argument("--test-classes-dir", type=Path, default=Path("target/test-classes"))
    options = parser.parse_args()
    options.report_dir.mkdir(parents=True, exist_ok=True)
    config = json.loads(options.config.read_text())
    java_home = Path(config["javaHome"])
    java = str(java_home) if java_home.name == "java" else str(java_home / "bin/java")
    preflight = ssh(options.config, "python3 -c " + shlex.quote("import os; from pathlib import Path; print(os.cpu_count()); print(Path('/proc/meminfo').read_text().splitlines()[:3]); assert Path('/sys/fs/cgroup/cgroup.controllers').exists()"))
    (options.report_dir / "remote-preflight.txt").write_bytes(preflight)
    remote = None
    with tempfile.TemporaryDirectory(prefix="zea-benchmark-") as staging:
        root = Path(staging)
        shutil.copytree(options.test_classes_dir / "com/akakata/benchmark", root / "classes/com/akakata/benchmark")
        (root / "lib").mkdir()
        for entry in options.classpath_file.read_text().strip().split(os.pathsep):
            shutil.copy2(entry, root / "lib" / Path(entry).name)
        manifest = {}
        for name, source in [("baseline", options.baseline_jar), ("candidate", options.candidate_jar)]:
            shutil.copy2(source, root / (name + ".jar"))
            manifest[name] = hashlib.sha256(source.read_bytes()).hexdigest()
        (root / "manifest.json").write_text(json.dumps(manifest))
        if options.matrix is not None:
            shutil.copy2(options.matrix, root / "matrix.json")
        properties = (options.test_classes_dir / "props/benchmark.properties").read_text()
        for threads in (1, 2):
            (root / f"classes/benchmark{threads}.properties").write_text(properties.replace("workerThreadCount=1", "workerThreadCount=" + str(threads))
                                                                      .replace("session.dispatcher.shards=1", "session.dispatcher.shards=" + str(threads)))
        (root / "quiet.xml").write_text('<configuration><appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="STDOUT"/></root></configuration>')
        for source in (Path(__file__), Path(__file__).with_name("test_report.py")):
            shutil.copy2(source, root / source.name)
        archive = root / "bundle.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            for source in root.iterdir():
                if source != archive:
                    bundle.add(source, arcname=source.name)
        try:
            remote = ssh(options.config, "umask 077; mktemp -d /tmp/zea-optimization.XXXXXXXX").decode().strip()
            if not remote.startswith("/tmp/zea-optimization.") or len(remote.split("/")) != 3:
                raise RuntimeError("Unexpected remote staging directory")
            (options.report_dir / "remote-state.json").write_text(json.dumps({"directory": remote, "artifacts": manifest}))
            print("Uploading isolated bundle: " + str(round(archive.stat().st_size / 1024 ** 2, 1)) + " MiB", flush=True)
            ssh(options.config, "tar -xzf - -C " + shlex.quote(remote), archive.read_bytes(), timeout=180)
            ssh(options.config, "python3 " + shlex.quote(remote + "/remote_benchmark.py") + " --worker " + shlex.quote(java), stream=options.report_dir / "remote-run.log")
        except Exception as error:
            save_report(options.report_dir, "remote-attempt-" + str(time.time_ns()),
                        {"status": "failed", "error": str(error), "remote_directory": remote})
            raise
        finally:
            if remote is not None:
                try:
                    archive_program = ("import sys,tarfile; from pathlib import Path; root=Path(" + repr(remote)
                                       + "); bundle=tarfile.open(fileobj=sys.stdout.buffer,mode='w|gz'); "
                                       + "bundle.add(root/'reports',arcname='reports') if (root/'reports').exists() else None; "
                                       + "[bundle.add(p,arcname=p.name) for p in root.glob('*.jfr')]; bundle.close()")
                    data = ssh(options.config, "python3 -c " + shlex.quote(archive_program))
                    evidence = options.report_dir / "remote-evidence.tar.gz"
                    evidence.write_bytes(data)
                    with tarfile.open(evidence) as bundle:
                        bundle.extractall(options.report_dir / "remote-evidence", filter="data")
                finally:
                    prefix = "zea-opt-" + remote.rsplit(".", 1)[-1].lower()
                    cleanup = "set -eu; for unit in " + shlex.quote(prefix) + "-*.service; do systemctl stop \"$unit\" 2>/dev/null || true; done; rm -rf -- " + shlex.quote(remote)
                    ssh(options.config, cleanup)
                    save_report(options.report_dir, "14-cleanup", {"remote_directory_removed": True, "unit_prefix": prefix})


if __name__ == "__main__":
    main()
