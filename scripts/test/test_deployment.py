"""네 파일 배포, 이미지 게시, 로컬 실행의 경계를 검증한다.

실행: uv run --offline --no-project scripts/test/test_deployment.py
설정 해석에는 실제 Compose CLI를 사용한다. Docker 데몬과 실제 시크릿은 사용하지 않는다.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DOCKER_CLI = shutil.which("docker")
COMPOSE_FILE = "docker-compose-deployment.yaml"
DEV_COMPOSE_FILE = "docker-compose.yml"

DOCKER_STUB = """#!/usr/bin/env bash
set -eu
printf '%s\\t' "${TAG-}" "${APP_PLATFORM-}" "${APP_PULL_POLICY-}" "${DB_POOL_SIZE-}" "${APP_PORT-}" "${LOADTEST_PORT-}" "${DEV_APP_PORT-}" "${MATEON_TEST_MODE-}" "${AI_STUB_DOCKERHUB_IMAGE-}" "${COMPOSE_PROFILES-}" "$PWD" "$@" >> "$DOCKER_CALLS"
printf '\\n' >> "$DOCKER_CALLS"
case "$1 ${2-}" in
  'version --format')
    [[ "${VERSION_EXIT:-0}" == 0 ]] || exit "$VERSION_EXIT"
    printf 'linux/amd64\\n' ;;
  'container inspect') exit "${CONTAINER_INSPECT_EXIT:-1}" ;;
  'update --restart=no') exit "${UPDATE_EXIT:-0}" ;;
  'buildx create') printf 'mateon-builder\\n' ;;
  'buildx build')
    for argument in "$@"; do
      if [[ "$argument" == */Dockerfile.ai-stub ]]; then exit "${STUB_BUILD_EXIT:-0}"; fi
    done
    exit "${BUILD_EXIT:-0}" ;;
  'login --username') cat >/dev/null; exit "${LOGIN_EXIT:-0}" ;;
esac
if [[ "$1" == compose ]]; then
  for argument in "$@"; do
    case "$argument" in
      config)
        [[ "${CONFIG_EXIT:-0}" == 0 ]] || exit "$CONFIG_EXIT"
        app=app
        port=${APP_PORT:-8081}
        if [[ -n "${MATEON_TEST_MODE-}" ]]; then
          app=loadtest-app
          port=${LOADTEST_PORT:-18081}
        fi
        cat <<CONFIG
services:
  $app:
    environment:
      DB_POOL_SIZE: "${DB_POOL_SIZE:-10}"
      JWT_SECRET: must-not-print
    image: eagleindesert/mateon-backend:${TAG:-v9.8.7}
    ports:
      - target: 8080
        published: "$port"
CONFIG
        exit 0 ;;
      pull) exit "${PULL_EXIT:-0}" ;;
      up) exit "${UP_EXIT:-0}" ;;
    esac
  done
fi
"""


def compose_prefix(mode="server"):
    command = ["compose", "--env-file", ".env", "--env-file", ".env.secret",
               "-f", COMPOSE_FILE, "--profile", "server" if mode == "server" else "loadtest"]
    if mode != "server":
        command += ["-p", "mateon-loadtest"]
    return command


class DeploymentScriptsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="mateon-deployment-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.app = self.directory / "app with spaces"
        self.app.mkdir()
        self.copy("start.sh")
        self.copy(COMPOSE_FILE)
        (self.app / ".env").write_text(
            "TAG=v9.8.7\nJWT_SECRET=overridden-by-secret-file\nMAIL_USERNAME=test@example.com\n",
            encoding="utf-8",
        )
        (self.app / ".env.secret").write_text(
            "JWT_SECRET=test-value-only\nPOSTGRES_PASSWORD=database-test-only\n", encoding="utf-8",
        )
        binary_directory = self.directory / "bin"
        binary_directory.mkdir()
        docker = binary_directory / "docker"
        docker.write_text(DOCKER_STUB, encoding="utf-8")
        docker.chmod(0o755)
        self.calls_file = self.directory / "calls.tsv"
        self.environment = os.environ.copy()
        for key in (
            "TAG", "APP_PLATFORM", "APP_PULL_POLICY", "DOCKERHUB_USERNAME", "DOCKERHUB_IMAGE",
            "DOCKERHUB_TOKEN", "COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_ENV_FILES",
            "DB_POOL_SIZE", "APP_PORT", "LOADTEST_PORT", "LOADTEST_BIND_ADDRESS", "MATEON_TEST_MODE",
            "AI_BASE_URL", "OPENAI_API_KEY", "AI_STUB_DOCKERHUB_IMAGE", "AI_STUB_TAG", "DEV_APP_PORT",
            "COMPOSE_PROJECT_NAME", "POSTGRES_PASSWORD", "JWT_SECRET",
        ):
            self.environment.pop(key, None)
        self.environment.update(
            PATH=f"{binary_directory}:{self.environment['PATH']}",
            DOCKER_CALLS=str(self.calls_file),
        )

    def copy(self, relative_path):
        destination = self.app / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(PROJECT_ROOT / relative_path, destination)

    def prepare_build(self):
        for relative_path in (
            "build-push.sh", "scripts/build-start.sh", "scripts/docker/deploy-dockerhub.sh",
            "Dockerfile", ".dockerignore", DEV_COMPOSE_FILE, "scripts/test/jmeter/Dockerfile.ai-stub",
        ):
            self.copy(relative_path)

    def run_script(self, script="start.sh", arguments=(), **environment):
        return subprocess.run(
            ["bash", str(self.app / script), *arguments], cwd=self.directory,
            env=self.environment | environment, capture_output=True, text=True, check=False,
        )

    def calls(self):
        if not self.calls_file.exists():
            return []
        names = ("tag", "platform", "pull_policy", "pool", "port", "test_port", "dev_port",
                 "mode", "stub_image", "profiles", "cwd")
        result = []
        for line in self.calls_file.read_text(encoding="utf-8").splitlines():
            values = line.rstrip("\t").split("\t")
            result.append(dict(zip(names, values)) | {"arguments": values[len(names):]})
        return result

    def compose_calls(self):
        return [call for call in self.calls() if call["arguments"][0] == "compose"]

    def builds(self):
        return [call["arguments"] for call in self.calls() if call["arguments"][:2] == ["buildx", "build"]]

    def deployment_config(self, mode="server", **environment):
        result = subprocess.run(
            [DOCKER_CLI, *compose_prefix(mode), "config", "--format", "json"],
            cwd=self.app, env=self.environment | environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def development_config(self, **environment):
        result = subprocess.run(
            [DOCKER_CLI, "compose", "-f", DEV_COMPOSE_FILE, "--profile", "app", "config", "--format", "json"],
            cwd=self.app, env=self.environment | environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_start_needs_only_four_files_in_every_mode(self):
        self.assertEqual({p.name for p in self.app.iterdir()}, {"start.sh", COMPOSE_FILE, ".env", ".env.secret"})
        for mode in ("server", "loadtest", "ai-stub"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script(arguments=["--mode", mode])
                self.assertEqual(result.returncode, 0, result.stderr)
                prefix = compose_prefix(mode)
                calls = self.calls()
                self.assertEqual(len(calls), 3)
                for call in calls:
                    self.assertEqual(call["arguments"][:len(prefix)], prefix)
                    self.assertEqual(call["cwd"], str(self.app))
                self.assertEqual(calls[1]["arguments"][len(prefix):], ["pull", "--policy", "always"])
                self.assertEqual(calls[2]["arguments"][len(prefix):],
                                 ["up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180"])
                self.assertIn("DB 풀: 10", result.stdout)
                self.assertNotIn("must-not-print", result.stdout)

    def test_version_overrides_shell_without_editing_configuration_files(self):
        before = {name: (self.app / name).read_bytes() for name in (".env", ".env.secret")}
        result = self.run_script(arguments=["v1.2.3"], TAG="old")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(call["tag"] == "v1.2.3" for call in self.calls()))
        for name, content in before.items():
            self.assertEqual((self.app / name).read_bytes(), content)

    def test_plain_start_preserves_shell_tag(self):
        result = self.run_script(TAG="v2.0.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[0]["tag"], "v2.0.0")

    def test_pull_failure_does_not_start_an_old_image(self):
        for mode in ("server", "loadtest", "ai-stub"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script(arguments=["--mode", mode], PULL_EXIT="9", APP_PULL_POLICY="never")
                self.assertEqual(result.returncode, 9)
                self.assertEqual(self.calls()[-1]["arguments"][-3:], ["pull", "--policy", "always"])
                self.assertFalse(any("up" in call["arguments"] for call in self.calls()))

    def test_start_propagates_compose_exit_code(self):
        self.assertEqual(self.run_script(UP_EXIT="17").returncode, 17)

    def test_invalid_arguments_do_not_call_docker(self):
        self.prepare_build()
        for script in ("start.sh", "scripts/build-start.sh", "build-push.sh"):
            arguments_list = [[""], ["bad/tag"], ["one", "two"], ["--mode", "production"],
                              ["--tag"], ["--tag", "--mode", "ai-stub"],
                              ["v1", "--tag", "v2"], ["--mode", "loadtest", "--mode", "server"]]
            if script != "build-push.sh":
                arguments_list += [["--port", "65536"], ["--port", "0"], ["--port", "oops"],
                                   ["--db-pool-size", "-1"], ["--db-pool-size", "10001"]]
            for arguments in arguments_list:
                with self.subTest(script=script, arguments=arguments):
                    self.assertEqual(self.run_script(script, arguments).returncode, 2)
        self.assertEqual(self.calls(), [])

    def test_flags_override_settings_without_printing_secrets(self):
        for mode in ("server", "loadtest", "ai-stub"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script(arguments=["--mode", mode, "--tag", "v1.2.3",
                                                    "--db-pool-size", "20", "--port", "18082"],
                                         TAG="old", DB_POOL_SIZE="5", APP_PORT="8888", LOADTEST_PORT="9999")
                self.assertEqual(result.returncode, 0, result.stderr)
                for call in self.calls():
                    self.assertEqual((call["tag"], call["pool"]), ("v1.2.3", "20"))
                    self.assertEqual(call["port" if mode == "server" else "test_port"], "18082")
                self.assertIn("HTTP 포트: 18082", result.stdout)
                self.assertNotIn("JWT_SECRET", result.stdout)

    def test_config_failure_prevents_pull_and_up(self):
        result = self.run_script(CONFIG_EXIT="7")
        self.assertEqual(result.returncode, 7)
        self.assertEqual(len(self.calls()), 1)
        self.assertIn("config", self.calls()[0]["arguments"])

    def test_start_ignores_inherited_profiles_from_another_mode(self):
        for mode in ("server", "loadtest"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script(arguments=["--mode", mode], COMPOSE_PROFILES="server,loadtest")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue(all(call["profiles"] in ("", "server" if mode == "server" else "loadtest")
                                    for call in self.calls()))

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_four_file_server_configuration_loads_secret_and_keeps_db_credentials_consistent(self):
        services = self.deployment_config()["services"]
        self.assertEqual(set(services), {"app", "postgres"})
        app = services["app"]
        self.assertEqual(app["image"], "eagleindesert/mateon-backend:v9.8.7")
        self.assertEqual(app["platform"], "linux/arm64")
        self.assertEqual(app["environment"]["JWT_SECRET"], "test-value-only")
        self.assertEqual(app["environment"]["SPRING_DATASOURCE_PASSWORD"], "database-test-only")
        self.assertEqual(services["postgres"]["environment"]["POSTGRES_PASSWORD"], "database-test-only")
        self.assertNotIn("JWT_SECRET", services["postgres"]["environment"])
        self.assertNotIn("build", app)
        self.assertEqual(str(app["ports"][0]["published"]), "8081")

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_four_file_loadtest_configuration_is_isolated_and_never_forwards_real_secrets(self):
        config = self.deployment_config("loadtest", AI_BASE_URL="https://paid.invalid", AI_ROUTER_ENABLED="true",
                                        AI_EVENT_EMBEDDING_BACKFILL_ENABLED="true", OPENAI_API_KEY="must-not-forward",
                                        SPRING_AI_OPENAI_API_KEY="must-not-forward", DB_POOL_SIZE="23",
                                        LOADTEST_PORT="18083", APP_PORT="8081", DOCKERHUB_USERNAME="example",
                                        DOCKERHUB_IMAGE="custom-be", TAG="app-v1", AI_STUB_DOCKERHUB_IMAGE="custom-stub",
                                        AI_STUB_TAG="obsolete-tag", APP_PLATFORM="linux/amd64", APP_PULL_POLICY="never")
        services = config["services"]
        self.assertEqual(set(services), {"loadtest-app", "loadtest-postgres", "ai-stub"})
        app = services["loadtest-app"]
        self.assertEqual(app["image"], "example/custom-be:app-v1")
        self.assertEqual(services["ai-stub"]["image"], "example/custom-stub:app-v1")
        for name in ("loadtest-app", "ai-stub"):
            self.assertEqual(services[name]["platform"], "linux/amd64")
            self.assertEqual(services[name]["pull_policy"], "never")
        for name, service in services.items():
            self.assertNotIn("build", service)
            self.assertNotIn("container_name", service)
            expected_networks = {"entrance", "isolated"} if name == "loadtest-app" else {"isolated"}
            self.assertEqual(set(service["networks"]), expected_networks)
            self.assertTrue(all(volume["type"] != "bind" for volume in service.get("volumes", [])))
        self.assertTrue(config["networks"]["isolated"]["internal"])
        self.assertFalse(config["networks"]["entrance"].get("internal", False))
        self.assertEqual(services["loadtest-postgres"]["volumes"][0]["source"], "loadtest-data")
        self.assertNotIn("ports", services["loadtest-postgres"])
        self.assertNotIn("ports", services["ai-stub"])
        env = app["environment"]
        self.assertEqual(env["SPRING_DATASOURCE_URL"], "jdbc:postgresql://loadtest-postgres:5432/mateon_loadtest")
        self.assertEqual(env["AI_BASE_URL"], "http://ai-stub:8000")
        self.assertEqual(env["AI_ROUTER_ENABLED"], "false")
        self.assertEqual(env["AI_EVENT_EMBEDDING_BACKFILL_ENABLED"], "false")
        self.assertEqual(env["OPENAI_API_KEY"], "")
        self.assertEqual(env["SPRING_AI_OPENAI_API_KEY"], "")
        self.assertEqual(str(env["DB_POOL_SIZE"]), "23")
        self.assertEqual(len(app["ports"]), 1)
        self.assertEqual(app["ports"][0]["target"], 8080)
        self.assertEqual(str(app["ports"][0]["published"]), "18083")
        self.assertEqual(app["ports"][0]["host_ip"], "127.0.0.1")
        serialized = json.dumps(services)
        for secret in ("must-not-forward", "test-value-only", "database-test-only", "https://paid.invalid"):
            self.assertNotIn(secret, serialized)

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_loadtest_default_port_does_not_inherit_server_port(self):
        services = self.deployment_config("loadtest", APP_PORT="8081")["services"]
        self.assertEqual(str(services["loadtest-app"]["ports"][0]["published"]), "18081")

    def test_build_uses_same_image_and_platform_when_recreating_development_app(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", [
            "--tag", "v2.3.4", "--platform", "linux/amd64", "--username", "example", "--image-name", "custom-be",
            "--db-pool-size", "20", "--port", "8082",
        ], DB_POOL_SIZE="5", APP_PORT="8888", APP_PULL_POLICY="always")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.builds()), 1)
        self.assertIn("example/custom-be:v2.3.4", self.builds()[0])
        self.assertIn("--load", self.builds()[0])
        self.assertNotIn("--push", self.builds()[0])
        last = self.calls()[-1]
        self.assertEqual(last["arguments"], ["compose", "-f", DEV_COMPOSE_FILE, "--profile", "app", "up", "-d",
                                             "--no-build", "--force-recreate", "--wait", "--wait-timeout", "180", "app"])
        self.assertEqual((last["tag"], last["platform"], last["pull_policy"]), ("v2.3.4", "linux/amd64", "never"))
        self.assertEqual((last["pool"], last["dev_port"]), ("20", "8082"))

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_development_compose_uses_bootrun_database_and_separate_port(self):
        self.prepare_build()
        config = self.development_config(APP_PORT="8081")
        app = config["services"]["app"]
        postgres = config["services"]["postgres"]
        self.assertEqual(str(app["ports"][0]["published"]), "8080")
        self.assertEqual(str(postgres["ports"][0]["published"]), "5432")
        self.assertEqual(postgres["volumes"][0]["source"], "mateon-postgres-dev-data")
        self.assertNotIn("mateon-postgres-data", config["volumes"])
        self.assertEqual(app["environment"]["JWT_SECRET"], "test-value-only")
        self.assertNotIn("JWT_SECRET", postgres["environment"])
        self.assertEqual(app["depends_on"]["postgres"]["condition"], "service_healthy")
        self.assertNotIn("restart", app)
        result = subprocess.run([DOCKER_CLI, "compose", "-f", DEV_COMPOSE_FILE, "config", "--services"],
                                cwd=self.app, env=self.environment, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["postgres"])

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_development_compose_accepts_built_image_and_explicit_port(self):
        self.prepare_build()
        app = self.development_config(DOCKERHUB_USERNAME="example", DOCKERHUB_IMAGE="custom-be", TAG="local-test",
                                      APP_PLATFORM="linux/amd64", APP_PULL_POLICY="never", DEV_APP_PORT="8082",
                                      APP_PORT="8081", DB_POOL_SIZE="20")["services"]["app"]
        self.assertEqual(app["image"], "example/custom-be:local-test")
        self.assertEqual(app["pull_policy"], "never")
        self.assertEqual(app["platform"], "linux/amd64")
        self.assertEqual(str(app["ports"][0]["published"]), "8082")
        self.assertEqual(str(app["environment"]["DB_POOL_SIZE"]), "20")

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_healthcheck_requires_http_200(self):
        self.prepare_build()
        commands = [self.development_config()["services"]["app"]["healthcheck"]["test"][3],
                    self.deployment_config()["services"]["app"]["healthcheck"]["test"][3],
                    self.deployment_config("loadtest")["services"]["loadtest-app"]["healthcheck"]["test"][3]]
        for command in commands:
            command = command.replace("$$", "$")
            for response, success in ((b"HTTP/1.1 200 OK\r\n", True),
                                      (b"HTTP/1.1 503 Service Unavailable\r\n", False), (b"", False)):
                with self.subTest(response=response), tempfile.TemporaryFile() as request, tempfile.TemporaryFile() as reply:
                    reply.write(response)
                    reply.seek(0)
                    probe = command.replace("exec 3<>/dev/tcp/127.0.0.1/8080",
                                            f"exec 3>&{request.fileno()} 4<&{reply.fileno()}").replace("<&3", "<&4")
                    result = subprocess.run(["bash", "-ec", probe], pass_fds=(request.fileno(), reply.fileno()),
                                            capture_output=True, text=True, timeout=5, check=False)
                    self.assertEqual(result.returncode == 0, success, result.stderr)
                    request.seek(0)
                    self.assertIn(b"GET /health HTTP/1.1\r\n", request.read())

    def test_default_build_selects_development_stack_and_native_platform(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        for call in self.compose_calls():
            self.assertEqual(call["arguments"][:5], ["compose", "-f", DEV_COMPOSE_FILE, "--profile", "app"])
        self.assertEqual((self.calls()[-1]["tag"], self.calls()[-1]["platform"]), ("local", "linux/amd64"))

    def test_development_start_failure_is_reported(self):
        self.prepare_build()
        self.assertEqual(self.run_script("scripts/build-start.sh", UP_EXIT="17").returncode, 17)
        self.assertIn("--wait", self.calls()[-1]["arguments"])

    def test_unavailable_docker_has_actionable_message_before_build(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", VERSION_EXIT="1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Docker Desktop 또는 Docker Engine을 시작", result.stderr)
        self.assertEqual(len(self.calls()), 1)

    def test_test_build_modes_build_both_images_before_starting_unified_stack(self):
        self.prepare_build()
        for mode in ("loadtest", "ai-stub"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script("scripts/build-start.sh", [
                    "--mode", mode, "--tag", "local-test", "--db-pool-size", "31", "--port", "18084",
                    "--platform", "linux/amd64", "--username", "example", "--image-name", "custom-be",
                ], AI_STUB_DOCKERHUB_IMAGE="custom-stub", AI_STUB_TAG="obsolete", APP_PULL_POLICY="always")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(self.builds()), 2)
                for build, image in zip(self.builds(), ("custom-be", "custom-stub")):
                    self.assertIn(f"example/{image}:local-test", build)
                    self.assertIn("--load", build)
                    self.assertNotIn("--push", build)
                prefix = compose_prefix(mode)
                for call in self.compose_calls():
                    self.assertEqual(call["arguments"][:len(prefix)], prefix)
                    self.assertEqual((call["mode"], call["pool"], call["test_port"]), (mode, "31", "18084"))
                    self.assertEqual((call["tag"], call["pull_policy"]), ("local-test", "never"))
                    self.assertNotIn("pull", call["arguments"])
                self.assertEqual(self.calls()[-1]["arguments"][len(prefix):],
                                 ["up", "-d", "--no-build", "--force-recreate", "--wait", "--wait-timeout", "180",
                                  "loadtest-app"])

    def test_build_config_failure_prevents_build_and_start(self):
        self.prepare_build()
        for mode in ("server", "loadtest", "ai-stub"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script("scripts/build-start.sh", ["--mode", mode, "--platform", "linux/amd64"],
                                         CONFIG_EXIT="7")
                self.assertEqual(result.returncode, 7)
                self.assertEqual(self.builds(), [])
                self.assertEqual(len(self.calls()), 1)

    def test_failed_build_never_starts_old_images_and_stops_builder(self):
        self.prepare_build()
        for failure in ({"BUILD_EXIT": "8"}, {"STUB_BUILD_EXIT": "9"}):
            with self.subTest(failure=failure):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script("scripts/build-start.sh", ["--mode", "loadtest"], **failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.calls()[-1]["arguments"], ["buildx", "stop", "mateon-builder"])
                self.assertFalse(any("up" in call["arguments"] for call in self.compose_calls()))
                self.assertEqual(len(self.builds()), 1 if "BUILD_EXIT" in failure else 2)

    def test_builder_disables_restart_for_new_and_existing_containers(self):
        self.prepare_build()
        for exists in ("0", "1"):
            with self.subTest(exists=exists):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script("build-push.sh", ["--tag", "v1", "--username", "example", "--no-push"],
                                         CONTAINER_INSPECT_EXIT=exists)
                self.assertEqual(result.returncode, 0, result.stderr)
                commands = [call["arguments"] for call in self.calls()]
                create = next(command for command in commands if command[:2] == ["buildx", "create"])
                self.assertIn("restart-policy=no", create)
                self.assertIn("docker-container", create)
                self.assertEqual(commands[-1], ["buildx", "stop", "mateon-builder"])
                update = ["update", "--restart=no", "buildx_buildkit_mateon-builder0"]
                self.assertEqual(update in commands, exists == "0")

    def test_restart_policy_failure_prevents_build(self):
        self.prepare_build()
        result = self.run_script("build-push.sh", ["--tag", "v1", "--username", "example"],
                                 CONTAINER_INSPECT_EXIT="0", UPDATE_EXIT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.builds(), [])

    def test_push_publishes_only_required_images_with_matching_versions_and_platforms(self):
        self.prepare_build()
        for mode, count in (("server", 1), ("loadtest", 2), ("ai-stub", 2)):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script("build-push.sh", ["--mode", mode, "--tag", "v1.2.3", "--username", "example",
                                                          "--platform", "linux/arm64", "--no-latest"])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(self.builds()), count)
                for build, image in zip(self.builds(), ("mateon-backend", "mateon-ai-stub")):
                    self.assertIn(f"example/{image}:v1.2.3", build)
                    self.assertEqual(build[build.index("--platform") + 1], "linux/arm64")
                    self.assertIn("--push", build)
                    self.assertNotIn("--load", build)
                    self.assertFalse(any(argument.endswith(":latest") for argument in build))
                self.assertEqual(self.compose_calls(), [])

    def test_publish_failure_is_reported_and_builder_is_cleaned_up(self):
        self.prepare_build()
        for failure in ({"BUILD_EXIT": "8"}, {"STUB_BUILD_EXIT": "9"}):
            with self.subTest(failure=failure):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script("build-push.sh", ["--mode", "loadtest", "--tag", "v1", "--username", "example"],
                                         **failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.calls()[-1]["arguments"], ["buildx", "stop", "mateon-builder"])
                self.assertNotIn("빌드 및 push 완료", result.stdout)

    def test_login_failure_prevents_publishing(self):
        self.prepare_build()
        result = self.run_script("build-push.sh", ["--tag", "v1", "--username", "example"],
                                 DOCKERHUB_TOKEN="test-token-only", LOGIN_EXIT="12")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.builds(), [])
        self.assertNotIn("test-token-only", result.stdout + result.stderr)

    def test_old_publish_entrypoint_forwards_arguments(self):
        self.prepare_build()
        result = self.run_script("scripts/docker/deploy-dockerhub.sh", ["--mode", "loadtest", "--tag", "v1",
                                                                       "--username", "example", "--no-push", "--no-latest"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.builds()), 2)
        self.assertTrue(all("--load" in build and "--push" not in build for build in self.builds()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
