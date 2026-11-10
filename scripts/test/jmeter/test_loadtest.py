"""AI 호출 차단·카운터 경합·결과 판정을 검증한다. Docker 데몬이나 외부 서비스는 사용하지 않는다."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


stub = load("mateon_ai_stub", ROOT / "scripts/debug/ai-stub/_stub_ai_server.py")
runner = load("mateon_loadtest_runner", Path(__file__).with_name("runner.py"))


class Socket:
    def __init__(self, request):
        self.input = request
        self.output = bytearray()

    def makefile(self, *args):
        return io.BytesIO(self.input)

    def sendall(self, data):
        self.output.extend(data)


class StubContractTest(unittest.TestCase):
    def setUp(self):
        stub.args = stub.parser.parse_args(["--quiet", "--expected-secret", "loadtest-only"])
        stub.stats = stub.RequestStats()

    def request(self, path, method="GET", body=None, secret="loadtest-only"):
        raw = json.dumps(body).encode() if body is not None else b""
        headers = (f"{method} {path} HTTP/1.1\r\nHost: localhost\r\n"
                   f"Content-Length: {len(raw)}\r\nX-Internal-Secret: {secret}\r\n\r\n").encode()
        socket = Socket(headers + raw)
        stub.Handler(socket, ("127.0.0.1", 12345), SimpleNamespace())
        head, payload = bytes(socket.output).split(b"\r\n\r\n", 1)
        return int(head.split(b" ")[1]), json.loads(payload)

    def test_loadtest_counts_and_rejects_ai_calls_even_without_valid_secret(self):
        stub.args.mode = "loadtest"
        status, body = self.request("/intents/extract", "POST", {"messages": []}, secret="wrong")
        self.assertEqual(status, 503)
        self.assertIn("AI", body["detail"])
        self.assertEqual(stub.stats.snapshot()["total"], 1)
        self.assertEqual(stub.stats.snapshot()["denied"], 1)
        self.assertEqual(stub.stats.snapshot()["active"], 0)

    def test_health_and_management_requests_do_not_count_as_ai(self):
        self.assertEqual(self.request("/__stub")[1]["stub"], "mateon-ai-stub")
        self.assertEqual(self.request("/__stub/stats")[1]["total"], 0)
        self.assertEqual(self.request("/__stub/reset", "POST")[1]["total"], 0)
        self.assertEqual(stub.stats.snapshot()["generation"], 1)

    def test_unknown_routes_and_methods_are_counted(self):
        for method in ("GET", "POST", "PUT"):
            self.assertEqual(self.request("/accidental-ai-path", method)[0], 404)
        self.assertEqual(stub.stats.snapshot()["total"], 3)

    def test_ai_stub_preserves_vector_contract(self):
        stub.args.mode = "ai-stub"
        status, body = self.request("/internal/contests/embedding:refresh", "POST", {"event_id": 7})
        self.assertEqual(status, 200)
        self.assertEqual(body["event_id"], 7)
        self.assertEqual(len(body["embedding_vector"]), 1536)
        self.assertEqual(stub.stats.snapshot()["active"], 0)

    def test_reset_during_request_is_rejected(self):
        stub.stats.begin("/intents/extract")
        self.assertEqual(self.request("/__stub/reset", "POST")[0], 409)
        self.assertEqual(stub.stats.snapshot()["total"], 1)
        stub.stats.finish()

    def test_unauthorized_business_request_is_still_counted(self):
        self.assertEqual(self.request("/intents/extract", "POST", {}, secret="wrong")[0], 401)
        self.assertEqual(stub.stats.snapshot()["total"], 1)
        self.assertEqual(stub.stats.snapshot()["active"], 0)

    def test_concurrent_counters_and_serials_do_not_lose_requests(self):
        def invoke(_):
            stub.stats.begin("/recommendations/reason")
            number = stub.stats.next_serial("reason")
            stub.stats.finish()
            return number
        with ThreadPoolExecutor(max_workers=16) as pool:
            numbers = list(pool.map(invoke, range(1000)))
        self.assertEqual(len(set(numbers)), 1000)
        self.assertEqual(stub.stats.snapshot()["paths"], {"/recommendations/reason": 1000})
        self.assertEqual(stub.stats.snapshot()["active"], 0)


class ResultVerificationTest(unittest.TestCase):
    def setUp(self):
        self.baseline = dict(instance_id="test", generation=1, total=0, active=0, denied=0)
        self.samples = {"조회": dict(samples=2, errors=0)}

    def test_core_rest_fails_on_hidden_ai_call_despite_successful_http(self):
        errors = runner.verify("core-rest", self.baseline, self.baseline | {"total": 1}, self.samples, 0)
        self.assertTrue(errors)

    def test_reset_and_restart_cannot_hide_ai_calls(self):
        for changed in ({"instance_id": "restarted"}, {"generation": 2}):
            self.assertTrue(runner.verify("core-rest", self.baseline, self.baseline | changed, self.samples, 0))

    def test_empty_or_failed_jmeter_results_fail(self):
        for samples, code in (({}, 0), ({"조회": {"samples": 2, "errors": 1}}, 0), (self.samples, 1)):
            self.assertTrue(runner.verify("core-rest", self.baseline, self.baseline, samples, code))

    def test_ai_stub_requires_an_actual_stub_call(self):
        self.assertTrue(runner.verify("ai-stub", self.baseline, self.baseline, self.samples, 0))
        self.assertFalse(runner.verify("ai-stub", self.baseline, self.baseline | {"total": 2}, self.samples, 0))

    def test_jtl_summary_counts_assertion_failure_and_percentiles_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jtl"
            path.write_text("label,elapsed,success\n조회,10,true\n조회,30,true\n조회,5,false\n", encoding="utf-8")
            result = runner.summarize_jtl(path)["조회"]
        self.assertEqual(result, dict(samples=3, errors=1, p50_ms=10, p95_ms=30, p99_ms=30))

    def test_plans_are_valid_xml_and_assign_a_fixed_account_per_thread(self):
        for suite in ("core-rest", "ai-stub"):
            root = ET.parse(Path(__file__).parent / "plans" / f"{suite}.jmx").getroot()
            csv_file = root.find(".//CSVDataSet/stringProp[@name='filename']")
            self.assertIn("${__threadNum}", csv_file.text)
            self.assertIsNotNone(root.find(".//JSR223Assertion"))
            self.assertIsNotNone(root.find(".//HTTPSamplerProxy"))


class RunningStackGuardTest(unittest.TestCase):
    def setUp(self):
        self.internal = True
        self.env = {
            "AI_BASE_URL": "http://ai-stub:8000", "AI_INTERNAL_SECRET": "loadtest-only",
            "AI_ROUTER_ENABLED": "false", "AI_EVENT_EMBEDDING_BACKFILL_ENABLED": "false",
            "OPENAI_API_KEY": "", "SPRING_AI_OPENAI_API_KEY": "",
            "SPRING_AI_OPENAI_BASE_URL": "http://ai-stub:8000",
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres:5432/mateon_loadtest",
            "JWT_SECRET": runner.TEST_KEY, "DB_POOL_SIZE": "20",
        }
        self.identity = {"stub": "mateon-ai-stub", "mode": "loadtest", "instance_id": "same-stack"}
        self.http_identity = dict(self.identity)

    def docker(self, command, **kwargs):
        if "ps" in command:
            return command[-1]
        if command[:2] == ["docker", "inspect"]:
            return json.dumps([{
                "Config": {"Labels": {"com.docker.compose.project": runner.PROJECT},
                           "Env": [f"{key}={value}" for key, value in self.env.items()], "Image": "test:v1"},
                "State": {"Running": True},
                "NetworkSettings": {"Networks": {"isolated": {}},
                                    "Ports": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18081"}]}},
            }])
        if command[:3] == ["docker", "network", "inspect"]:
            return json.dumps([{"Internal": self.internal}])
        self.fail(f"예상하지 않은 Docker 호출: {command[:3]}")

    def http(self, url):
        return self.http_identity if url.endswith("/__loadtest") else {"data": {"status": "UP"}}

    def check(self):
        with patch.object(runner, "execute", side_effect=self.docker), \
             patch.object(runner, "stub_request", return_value=self.identity), \
             patch.object(runner, "http_json", side_effect=self.http):
            return runner.check_stack("core-rest")

    def test_running_internal_stack_passes_and_returns_actual_port(self):
        self.assertEqual(self.check()[:2], ("127.0.0.1", 18081))

    def test_network_with_external_connectivity_is_rejected(self):
        self.internal = False
        with self.assertRaisesRegex(RuntimeError, "외부 통신"):
            self.check()

    def test_real_ai_configuration_is_rejected_before_any_load(self):
        self.env["AI_BASE_URL"] = "https://paid.invalid"
        with self.assertRaisesRegex(RuntimeError, "실행 설정"):
            self.check()

    def test_same_mode_on_a_different_http_stack_is_rejected(self):
        self.http_identity["instance_id"] = "other-stack"
        with self.assertRaisesRegex(RuntimeError, "HTTP 접속 대상"):
            self.check()


if __name__ == "__main__":
    unittest.main(verbosity=2)
