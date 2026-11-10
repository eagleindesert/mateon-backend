"""배포 디렉터리의 독립성과 스크립트의 Docker 호출 순서를 검사한다.

실행: uv run --no-project scripts/test/test_deployment.py
실제 이미지 빌드·pull·컨테이너 실행은 하지 않는다.
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

DOCKER_STUB = """#!/usr/bin/env bash
set -eu
printf '%s\\t' "${TAG-}" "${APP_PLATFORM-}" "${APP_PULL_POLICY-}" "${DB_POOL_SIZE-}" "${APP_PORT-}" "${MATEON_TEST_MODE-}" "$PWD" "$@" >> "$DOCKER_CALLS"
printf '\\n' >> "$DOCKER_CALLS"
case "$1 ${2-}" in
  'version --format') printf 'linux/amd64\\n' ;;
  'container inspect') exit "${CONTAINER_INSPECT_EXIT:-1}" ;;
  'update --restart=no') exit "${UPDATE_EXIT:-0}" ;;
  'buildx create') printf 'mateon-builder\\n' ;;
  'buildx build') exit "${BUILD_EXIT:-0}" ;;
esac
if [[ "$1" == compose ]]; then
  for argument in "$@"; do
    case "$argument" in
      config)
        [[ "${CONFIG_EXIT:-0}" == 0 ]] || exit "$CONFIG_EXIT"
        cat <<EOF
services:
  app:
    environment:
      DB_POOL_SIZE: "${DB_POOL_SIZE:-10}"
      JWT_SECRET: must-not-print
    image: eagleindesert/mateon-backend:${TAG:-v9.8.7}
    ports:
      - target: 8080
        published: "${APP_PORT:-8081}"
EOF
        exit 0 ;;
      pull) exit "${PULL_EXIT:-0}" ;;
      up) exit "${UP_EXIT:-0}" ;;
    esac
  done
fi
"""


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
            "TAG=v9.8.7\nJWT_SECRET=test-value-only\nMAIL_USERNAME=test@example.com\n",
            encoding="utf-8",
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
            "DB_POOL_SIZE", "APP_PORT", "MATEON_TEST_MODE", "AI_BASE_URL", "OPENAI_API_KEY",
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
            "scripts/build-start.sh", "scripts/docker/deploy-dockerhub.sh", "Dockerfile", ".dockerignore",
        ):
            self.copy(relative_path)

    def run_script(self, script="start.sh", arguments=(), **environment):
        return subprocess.run(
            ["bash", str(self.app / script), *arguments],
            cwd=self.directory,
            env=self.environment | environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def calls(self):
        if not self.calls_file.exists():
            return []
        result = []
        for line in self.calls_file.read_text(encoding="utf-8").splitlines():
            tag, platform, pull_policy, pool, port, mode, cwd, *arguments = line.rstrip("\t").split("\t")
            result.append(dict(tag=tag, platform=platform, pull_policy=pull_policy,
                               pool=pool, port=port, mode=mode, cwd=cwd, arguments=arguments))
        return result

    def launch_calls(self):
        return [call for call in self.calls() if "config" not in call["arguments"]]

    def test_start_needs_only_three_files_and_matches_compose_up(self):
        self.assertEqual({p.name for p in self.app.iterdir()}, {"start.sh", COMPOSE_FILE, ".env"})
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.calls()), 2)
        self.assertEqual(self.launch_calls()[0]["arguments"], ["compose", "-f", COMPOSE_FILE, "up", "-d"])
        self.assertEqual(self.calls()[0]["cwd"], str(self.app))
        self.assertEqual(self.calls()[0]["tag"], "")
        self.assertIn("DB 풀: 10", result.stdout)
        self.assertNotIn("must-not-print", result.stdout)

    def test_version_overrides_environment_for_pull_and_up_without_editing_env(self):
        previous = (self.app / ".env").read_bytes()
        result = self.run_script(arguments=["v1.2.3"], TAG="old")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["arguments"][3:] for call in self.launch_calls()], [["pull"], ["up", "-d"]])
        self.assertTrue(all(call["tag"] == "v1.2.3" for call in self.calls()))
        self.assertEqual((self.app / ".env").read_bytes(), previous)

    def test_plain_start_preserves_shell_tag(self):
        result = self.run_script(TAG="v2.0.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[0]["tag"], "v2.0.0")

    def test_pull_failure_does_not_start_an_old_image(self):
        result = self.run_script(arguments=["v1.2.3"], PULL_EXIT="9")
        self.assertEqual(result.returncode, 9)
        self.assertEqual([call["arguments"][3:] for call in self.launch_calls()], [["pull"]])

    def test_start_propagates_compose_exit_code(self):
        self.assertEqual(self.run_script(UP_EXIT="17").returncode, 17)

    def test_invalid_arguments_do_not_call_docker(self):
        for arguments in ([""], ["bad/tag"], ["one", "two"], ["--mode", "production"],
                          ["--tag"], ["--port", "65536"], ["--port", "0"], ["--port", "oops"],
                          ["--db-pool-size", "-1"], ["--db-pool-size", "10001"],
                          ["v1", "--tag", "v2"], ["--mode", "loadtest", "--mode", "server"]):
            with self.subTest(arguments=arguments):
                self.assertEqual(self.run_script(arguments=arguments).returncode, 2)
        self.assertEqual(self.calls(), [])

    def test_flags_override_inherited_values_and_summary_shows_only_public_settings(self):
        result = self.run_script(arguments=["--tag", "v1.2.3", "--db-pool-size", "20", "--port", "18082"],
                                 TAG="old", DB_POOL_SIZE="5", APP_PORT="8888")
        self.assertEqual(result.returncode, 0, result.stderr)
        for call in self.calls():
            self.assertEqual((call["tag"], call["pool"], call["port"]), ("v1.2.3", "20", "18082"))
        self.assertIn("이미지: eagleindesert/mateon-backend:v1.2.3", result.stdout)
        self.assertIn("HTTP 포트: 18082", result.stdout)
        self.assertNotIn("JWT_SECRET", result.stdout)

    def test_config_failure_prevents_pull_and_up(self):
        result = self.run_script(arguments=["--tag", "v1.2.3"], CONFIG_EXIT="7")
        self.assertEqual(result.returncode, 7)
        self.assertEqual(self.launch_calls(), [])

    def test_test_modes_select_isolated_compose_for_both_pull_and_up(self):
        for mode in ("loadtest", "ai-stub"):
            with self.subTest(mode=mode):
                self.calls_file.unlink(missing_ok=True)
                result = self.run_script(arguments=["--mode", mode, "--tag", "v1.2.3"])
                self.assertEqual(result.returncode, 0, result.stderr)
                prefix = ["compose", "--env-file", "config/loadtest.properties", "-p", "mateon-loadtest",
                          "-f", "docker-compose-loadtest.yaml"]
                for call in self.calls():
                    self.assertEqual(call["arguments"][:len(prefix)], prefix)
                    self.assertEqual(call["mode"], mode)
                self.assertEqual(self.launch_calls()[0]["arguments"][len(prefix):], ["pull", "app", "postgres", "proxy"])
                self.assertEqual(self.launch_calls()[1]["arguments"][len(prefix):],
                                 ["up", "-d", "--build", "--wait", "--wait-timeout", "180"])

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_test_compose_ignores_real_ai_credentials_and_keeps_backend_network_internal(self):
        self.copy("docker-compose-loadtest.yaml")
        self.copy("config/loadtest.properties")
        result = subprocess.run(
            [DOCKER_CLI, "compose", "--env-file", "config/loadtest.properties", "-p", "mateon-loadtest",
             "-f", "docker-compose-loadtest.yaml", "config", "--format", "json"],
            cwd=self.app, env=self.environment | {
                "AI_BASE_URL": "https://paid.invalid", "AI_ROUTER_ENABLED": "true",
                "AI_EVENT_EMBEDDING_BACKFILL_ENABLED": "true", "OPENAI_API_KEY": "must-not-forward",
                "SPRING_AI_OPENAI_API_KEY": "must-not-forward", "DB_POOL_SIZE": "23", "APP_PORT": "18083",
            }, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        config = json.loads(result.stdout)
        services = config["services"]
        env = services["app"]["environment"]
        self.assertEqual(env["AI_BASE_URL"], "http://ai-stub:8000")
        self.assertEqual(env["AI_ROUTER_ENABLED"], "false")
        self.assertEqual(env["AI_EVENT_EMBEDDING_BACKFILL_ENABLED"], "false")
        self.assertEqual(env["OPENAI_API_KEY"], "")
        self.assertEqual(env["SPRING_AI_OPENAI_API_KEY"], "")
        self.assertNotIn("must-not-forward", result.stdout)
        self.assertEqual(str(env["DB_POOL_SIZE"]), "23")
        self.assertEqual(str(services["proxy"]["ports"][0]["published"]), "18083")
        for name in ("app", "postgres", "ai-stub"):
            self.assertEqual(set(services[name]["networks"]), {"isolated"})
            self.assertNotIn("ports", services[name])
            self.assertNotIn("container_name", services[name])
        self.assertTrue(config["networks"]["isolated"]["internal"])
        self.assertEqual(set(services["proxy"]["networks"]), {"entrance", "isolated"})

    @unittest.skipUnless(DOCKER_CLI, "Docker Compose CLI 가 필요하다 (데몬은 사용하지 않음)")
    def test_compose_loads_three_file_directory_with_secrets_from_one_env(self):
        result = subprocess.run(
            [DOCKER_CLI, "compose", "-f", COMPOSE_FILE, "config", "--format", "json"],
            cwd=self.app, env=self.environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        services = json.loads(result.stdout)["services"]
        self.assertEqual(services["app"]["image"], "eagleindesert/mateon-backend:v9.8.7")
        self.assertEqual(services["app"]["platform"], "linux/arm64")
        self.assertEqual(services["app"]["environment"]["JWT_SECRET"], "test-value-only")
        self.assertNotIn("JWT_SECRET", services["postgres"]["environment"])
        self.assertNotIn("build", services["app"])

    def test_build_uses_same_image_and_platform_when_recreating_app(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", [
            "--tag", "v2.3.4", "--platform", "linux/amd64", "--username", "example", "--image-name", "custom-be",
        ])
        self.assertEqual(result.returncode, 0, result.stderr)
        build = next(call for call in self.calls() if call["arguments"][:2] == ["buildx", "build"])
        self.assertIn("example/custom-be:v2.3.4", build["arguments"])
        self.assertIn("--load", build["arguments"])
        self.assertNotIn("--push", build["arguments"])
        self.assertEqual(self.calls()[-1]["arguments"], ["compose", "-f", COMPOSE_FILE, "up", "-d", "--force-recreate", "app"])
        self.assertEqual(self.calls()[-1]["tag"], "v2.3.4")
        self.assertEqual(self.calls()[-1]["platform"], "linux/amd64")
        self.assertEqual(self.calls()[-1]["pull_policy"], "never")

    def test_default_build_platform_matches_docker_daemon(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[-1]["platform"], "linux/amd64")

    def test_new_builder_disables_restart_in_persistent_configuration(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", ["--platform", "linux/arm64"])
        self.assertEqual(result.returncode, 0, result.stderr)
        create = next(call["arguments"] for call in self.calls() if call["arguments"][:2] == ["buildx", "create"])
        self.assertIn("restart-policy=no", create)
        self.assertIn("docker-container", create)
        self.assertIn(["buildx", "stop", "mateon-builder"], [call["arguments"] for call in self.calls()])

    def test_existing_builder_container_also_gets_restart_disabled(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", CONTAINER_INSPECT_EXIT="0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            ["update", "--restart=no", "buildx_buildkit_mateon-builder0"],
            [call["arguments"] for call in self.calls()],
        )

    def test_build_failure_stops_builder_and_does_not_recreate_app(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", BUILD_EXIT="8")
        self.assertNotEqual(result.returncode, 0)
        calls = [call["arguments"] for call in self.calls()]
        self.assertEqual(calls[-1], ["buildx", "stop", "mateon-builder"])
        self.assertFalse(any(call[:1] == ["compose"] and "up" in call for call in calls))

    def test_restart_policy_failure_prevents_build(self):
        self.prepare_build()
        result = self.run_script("scripts/build-start.sh", CONTAINER_INSPECT_EXIT="0", UPDATE_EXIT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(call["arguments"][:2] == ["buildx", "build"] for call in self.calls()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
