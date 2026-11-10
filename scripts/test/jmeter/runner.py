"""격리된 Compose 스택만 대상으로 JMeter를 실행한다. Python 표준 라이브러리만 사용한다."""

import argparse
import base64
import csv
from datetime import datetime, timezone
import fcntl
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
PROJECT = "mateon-loadtest"
TEST_KEY = "mateon-loadtest-key-only-0123456789abcdef"
COMPOSE = ["docker", "compose", "--env-file", str(ROOT / "config/loadtest.properties"),
           "-p", PROJECT, "-f", str(ROOT / "docker-compose-loadtest.yaml")]


def execute(command, *, stdin=None):
    result = subprocess.run(command, cwd=ROOT, input=stdin, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"명령 실패 ({result.returncode}): {' '.join(command[:4])}\n{result.stderr.strip()}")
    return result.stdout.strip()


def http_json(url):
    # 개발자 셸의 HTTP_PROXY가 테스트 요청을 다른 곳으로 보내지 않게 한다.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=10) as response:
        return json.load(response)


def stub_request(path, method="GET"):
    # 관리 API는 외부 포트로 노출하지 않고 스텁 컨테이너 안에서만 접근한다.
    code = ("import json,urllib.request; "
            "r=urllib.request.Request('http://127.0.0.1:8000/'+__import__('sys').argv[1],"
            "method=__import__('sys').argv[2]); "
            "print(urllib.request.urlopen(r,timeout=5).read().decode())")
    return json.loads(execute(COMPOSE + ["exec", "-T", "ai-stub", "uv", "run", "--offline",
                                        "--no-project", "python", "-c", code, path, method]))


def check_stack(suite):
    expected_mode = "loadtest" if suite == "core-rest" else "ai-stub"
    containers = {}
    for service in ("app", "postgres", "ai-stub", "proxy"):
        container_id = execute(COMPOSE + ["ps", "-q", service])
        if not container_id or "\n" in container_id:
            raise RuntimeError(f"{service} 컨테이너가 준비되지 않았습니다. start.sh --mode {expected_mode}를 실행하세요.")
        info = json.loads(execute(["docker", "inspect", container_id]))[0]
        labels = info["Config"].get("Labels", {})
        if labels.get("com.docker.compose.project") != PROJECT or not info["State"]["Running"]:
            raise RuntimeError("실행 중인 테스트 전용 Compose 프로젝트가 아닙니다.")
        containers[service] = info

    env = dict(entry.split("=", 1) for entry in containers["app"]["Config"]["Env"])
    fixed = {
        "AI_BASE_URL": "http://ai-stub:8000",
        "AI_INTERNAL_SECRET": "loadtest-only",
        "AI_ROUTER_ENABLED": "false",
        "AI_EVENT_EMBEDDING_BACKFILL_ENABLED": "false",
        "OPENAI_API_KEY": "",
        "SPRING_AI_OPENAI_API_KEY": "",
        "SPRING_AI_OPENAI_BASE_URL": "http://ai-stub:8000",
        "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres:5432/mateon_loadtest",
        "JWT_SECRET": TEST_KEY,
    }
    if any(env.get(key) != value for key, value in fixed.items()):
        raise RuntimeError("앱의 실제 실행 설정이 테스트 전용 설정과 다릅니다. 테스트를 중단합니다.")
    for service in ("app", "postgres", "ai-stub"):
        networks = containers[service]["NetworkSettings"]["Networks"]
        if not networks:
            raise RuntimeError(f"{service}의 격리 네트워크를 확인할 수 없습니다.")
        for network in networks:
            details = json.loads(execute(["docker", "network", "inspect", network]))[0]
            if not details.get("Internal"):
                raise RuntimeError(f"{service}가 외부 통신 가능한 네트워크에 연결되어 있습니다.")

    identity = stub_request("__stub")
    if identity.get("stub") != "mateon-ai-stub" or identity.get("mode") != expected_mode:
        raise RuntimeError(f"스텁 모드가 다릅니다. start.sh --mode {expected_mode}로 다시 실행하세요.")
    ports = containers["proxy"]["NetworkSettings"]["Ports"].get("8080/tcp") or []
    if not ports:
        raise RuntimeError("테스트 프록시의 공개 포트를 찾을 수 없습니다.")
    binding = next((port for port in ports if ":" not in port["HostIp"]), ports[0])
    host = binding["HostIp"]
    if host in ("0.0.0.0", "::", ""):
        host = "127.0.0.1"
    url_host = f"[{host}]" if ":" in host else host
    port = int(binding["HostPort"])
    base_url = f"http://{url_host}:{port}"
    marker = http_json(base_url + "/__loadtest")
    if marker != identity:
        raise RuntimeError("HTTP 접속 대상이 선택한 테스트 환경과 다릅니다.")
    if http_json(base_url + "/health").get("data", {}).get("status") != "UP":
        raise RuntimeError("앱이 아직 준비되지 않았습니다.")
    return host, port, identity, containers["app"]["Config"]["Image"], env.get("DB_POOL_SIZE")


def access_token(user_id, lifetime):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    now = int(time.time())
    header = encode({"alg": "HS256", "typ": "JWT"})
    payload = encode({"sub": str(user_id), "type": "access", "iat": now,
                      "exp": now + lifetime, "jti": str(uuid.uuid4())})
    message = header + b"." + payload
    signature = hmac.new(TEST_KEY.encode(), message, hashlib.sha256).digest()
    return (message + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()


def prepare_users(options, directory):
    command = COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-qAt", "-U", "loadtest",
                         "-d", "mateon_loadtest", "-v", "ON_ERROR_STOP=1",
                         "-v", f"users={options.users}", "-v", f"events={options.events}",
                         "-v", f"teams={options.teams}"]
    rows = json.loads(execute(command, stdin=(HERE / "seed.sql").read_text(encoding="utf-8")))
    if len(rows) != options.users:
        raise RuntimeError("테스트 사용자 데이터 수가 맞지 않습니다.")
    directory.mkdir(mode=0o700)
    for index, row in enumerate(rows, 1):
        row["token"] = access_token(row["user_id"], options.duration + 3600)
        filename = directory / f"user-{index}.csv"
        with filename.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=("user_id", "token", "event_id", "team_id", "room_id"))
            writer.writeheader()
            writer.writerow(row)
        filename.chmod(0o600)


def wait_for_idle(seconds=5, timeout=140):
    """스텁의 진행 중 요청이 없고 업무 카운터가 일정 시간 유지될 때까지 기다린다."""
    deadline = time.monotonic() + timeout
    previous = None
    stable_since = time.monotonic()
    while time.monotonic() < deadline:
        current = stub_request("__stub/stats")
        marker = (current["instance_id"], current["generation"], current["total"])
        if current["active"] or marker != previous:
            stable_since = time.monotonic()
        if not current["active"] and time.monotonic() - stable_since >= seconds:
            return current
        previous = marker
        time.sleep(1)
    raise RuntimeError("스텁 요청이 안정되지 않았습니다. 다른 테스트가 실행 중인지 확인하세요.")


def summarize_jtl(path):
    samples = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            item = samples.setdefault(row["label"], {"samples": 0, "errors": 0, "elapsed": []})
            item["samples"] += 1
            if row["success"].lower() == "true":
                item["elapsed"].append(int(row["elapsed"]))
            else:
                item["errors"] += 1
    for item in samples.values():
        elapsed = sorted(item.pop("elapsed"))
        for percentile in (50, 95, 99):
            item[f"p{percentile}_ms"] = elapsed[math.ceil(len(elapsed) * percentile / 100) - 1] if elapsed else None
    return samples


def verify(suite, baseline, final_stats, samples, jmeter_code):
    reasons = []
    if jmeter_code:
        reasons.append(f"JMeter 종료 코드: {jmeter_code}")
    if not samples or any(item["errors"] for item in samples.values()):
        reasons.append("요청 표본이 없거나 HTTP/응답 검증 실패가 있습니다.")
    if (final_stats["instance_id"], final_stats["generation"]) != (baseline["instance_id"], baseline["generation"]):
        reasons.append("측정 중 스텁이 재시작되거나 카운터가 초기화되었습니다.")
    if final_stats["active"]:
        reasons.append("아직 진행 중인 스텁 요청이 있습니다.")
    if suite == "core-rest" and final_stats["total"] != 0:
        reasons.append("일반 REST 테스트에서 AI 업무 호출이 발생했습니다.")
    if suite == "ai-stub" and (final_stats["total"] == 0 or final_stats["denied"]):
        reasons.append("AI 스텁 호출이 없거나 차단되었습니다.")
    return reasons


def arguments():
    parser = argparse.ArgumentParser(description="테스트 전용 Compose 스택에서 JMeter 실행 (실제 AI 호출 불가)")
    parser.add_argument("--suite", choices=("core-rest", "ai-stub"), default="core-rest")
    parser.add_argument("--users", type=int, default=10)
    parser.add_argument("--ramp-up", type=int, default=60)
    parser.add_argument("--duration", type=int, default=600)
    parser.add_argument("--events", type=int, default=1000)
    parser.add_argument("--teams", type=int, default=100)
    parser.add_argument("--jmeter", default="jmeter", help="JMeter 실행 파일 경로")
    options = parser.parse_args()
    if not 1 <= options.users <= 1000 or not 1 <= options.events <= 100000 or not 1 <= options.teams <= 10000:
        parser.error("users=1~1000, events=1~100000, teams=1~10000 범위를 사용하세요.")
    if not 0 <= options.ramp_up < options.duration <= 86400:
        parser.error("0 <= ramp-up < duration <= 86400이어야 합니다.")
    return options


def main():
    options = arguments()
    if not shutil.which(options.jmeter) or not shutil.which("docker"):
        print("Docker Compose와 JMeter가 필요합니다. JMeter 경로는 --jmeter로 지정할 수 있습니다.", file=sys.stderr)
        return 2
    reports = ROOT / "build/reports/jmeter"
    reports.mkdir(parents=True, exist_ok=True)
    with (reports / ".run.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("다른 JMeter 실행이 진행 중입니다.", file=sys.stderr)
            return 2
        return run(options, reports)


def run(options, reports):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = reports / f"{stamp}-{options.suite}-{uuid.uuid4().hex[:6]}"
    output.mkdir()
    identities = output / "users"
    verification = {"passed": False, "suite": options.suite, "options": vars(options)}
    try:
        host, port, identity, image, pool_size = check_stack(options.suite)
        verification.update(image=image, db_pool_size=pool_size, stub=identity)
        print(f"대상: {host}:{port} / {options.suite} / {options.users}명 / {options.duration}초", flush=True)
        prepare_users(options, identities)
        sizes = execute(COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-qAt", "-U", "loadtest",
                                   "-d", "mateon_loadtest", "-v", "ON_ERROR_STOP=1", "-c",
                                   "SELECT json_build_object('users', (SELECT count(*) FROM users), "
                                   "'events', (SELECT count(*) FROM events), "
                                   "'teams', (SELECT count(*) FROM teams), "
                                   "'chat_messages', (SELECT count(*) FROM chat_messages))"])
        verification["database_rows"] = json.loads(sizes)
        wait_for_idle()
        baseline = stub_request("__stub/reset", "POST")
        command = [options.jmeter, "-n", "-t", str(HERE / "plans" / f"{options.suite}.jmx"),
                   "-l", str(output / "results.jtl"), "-j", str(output / "jmeter.log"),
                   "-e", "-o", str(output / "dashboard"),
                   f"-Jhost={host}", f"-Jport={port}", f"-Jusers={options.users}",
                   f"-Jramp_up={options.ramp_up}", f"-Jduration={options.duration}",
                   f"-Jusers_dir={identities}", "-Jjmeter.save.saveservice.output_format=csv",
                   "-Jjmeter.save.saveservice.print_field_names=true",
                   "-Jjmeter.save.saveservice.response_data=false",
                   "-Jjmeter.save.saveservice.response_data.on_error=false",
                   "-Jjmeter.save.saveservice.samplerData=false",
                   "-Jjmeter.save.saveservice.requestHeaders=false"]
        result = subprocess.run(command, cwd=ROOT)
        final_stats = wait_for_idle()
        samples = summarize_jtl(output / "results.jtl")
        reasons = verify(options.suite, baseline, final_stats, samples, result.returncode)
        verification.update(passed=not reasons, errors=reasons, ai_calls=final_stats, samples=samples)
    except (RuntimeError, OSError, ValueError, KeyError, urllib.error.URLError) as error:
        verification["errors"] = [str(error)]
    except KeyboardInterrupt:
        verification["errors"] = ["사용자가 테스트를 중단했습니다."]
    finally:
        shutil.rmtree(identities, ignore_errors=True)
        (output / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"결과: {output}")
    for error in verification.get("errors", []):
        print(error, file=sys.stderr)
    print("검증 통과" if verification["passed"] else "검증 실패")
    return 0 if verification["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
