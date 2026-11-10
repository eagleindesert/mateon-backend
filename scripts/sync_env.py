""".env와 .env.secret에 각각의 예제 양식을 적용하고 기존 값을 보존한다.

실행: uv run scripts/sync_env.py [--dry-run]
"""

import argparse
from dataclasses import dataclass, replace
import os
from pathlib import Path
import re
import stat
import sys
import tempfile


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ASSIGNMENT = re.compile(
    r"^(?P<indent>[ \t]*)(?P<comment>#[ \t]*)?"
    r"(?P<export>export[ \t]+)?(?P<key>[A-Za-z_][A-Za-z0-9_.-]*)"
    r"(?P<separator>[ \t]*=)(?P<value>.*)$"
)


@dataclass(frozen=True)
class Assignment:
    key: str
    indent: str
    comment: str
    export: str
    separator: str
    value: str
    newline: str
    line_number: int

    def render(self) -> str:
        return (
            f"{self.indent}{self.comment}{self.export}{self.key}"
            f"{self.separator}{self.value}{self.newline}"
        )


@dataclass(frozen=True)
class EnvUpdate:
    path: Path
    previous: bytes | None
    content: bytes


def quoted_value_is_complete(value: str) -> bool:
    """따옴표 안의 #, =, 이스케이프 및 줄바꿈은 해석하지 않고 보존한다."""
    stripped = value.lstrip(" \t")
    if not stripped or stripped[0] not in ("'", '"'):
        return True

    quote = stripped[0]
    escaped = False
    for index, character in enumerate(stripped[1:], start=1):
        if escaped:
            escaped = False
        elif character == "\\":
            escaped = True
        elif character == quote:
            trailing = stripped[index + 1:].lstrip(" \t")
            if trailing and not trailing.startswith("#"):
                raise ValueError("닫는 따옴표 뒤에는 공백이나 주석만 올 수 있습니다")
            return True
    return False


def parse(content: str, label: str) -> list[str | Assignment]:
    records: list[str | Assignment] = []
    lines = content.splitlines(keepends=True)
    index = 0

    while index < len(lines):
        line = lines[index]
        line_number = index + 1
        body = line.rstrip("\r\n")
        newline = line[len(body):]
        match = ASSIGNMENT.fullmatch(body)
        index += 1

        if match is None:
            if body.strip() and not body.lstrip().startswith("#"):
                raise ValueError(f"{label}:{line_number}: KEY=VALUE 형식이 아닙니다")
            records.append(line)
            continue

        fields = match.groupdict(default="")
        value = fields.pop("value")
        try:
            while not quoted_value_is_complete(value):
                if index >= len(lines):
                    raise ValueError("값의 따옴표가 닫히지 않았습니다")
                continuation = lines[index]
                if fields["comment"] and not continuation.lstrip().startswith("#"):
                    raise ValueError("주석 처리한 여러 줄 값은 모든 줄에 #이 필요합니다")
                body = continuation.rstrip("\r\n")
                value += newline + body
                newline = continuation[len(body):]
                index += 1
        except ValueError as error:
            raise ValueError(f"{label}:{line_number}: {error}") from error

        records.append(Assignment(**fields, value=value, newline=newline,
                                  line_number=line_number))

    return records


def select_assignments(records: list[str | Assignment], label: str) -> dict[str, Assignment]:
    """활성 설정을 우선하며, 의미가 모호한 활성 키 중복은 수정 전에 거부한다."""
    selected: dict[str, Assignment] = {}
    for record in records:
        if not isinstance(record, Assignment):
            continue
        previous = selected.get(record.key)
        if previous is not None and not previous.comment and not record.comment:
            raise ValueError(
                f"{label}:{record.line_number}: 활성 키 {record.key}가 중복됩니다"
            )
        if previous is None or previous.comment:
            selected[record.key] = record
    return selected


def merge_env(
    example: str,
    existing: str,
    *,
    example_label: str = ".env.example",
    env_label: str = ".env",
) -> str:
    template_records = parse(example, example_label)
    template_values = select_assignments(template_records, example_label)
    existing_values = select_assignments(parse(existing, env_label), env_label)
    output: list[str] = []

    for record in template_records:
        if isinstance(record, str):
            output.append(record)
            continue

        current = existing_values.get(record.key)
        if current is not None and record is template_values[record.key]:
            # 템플릿의 위치/들여쓰기와 기존 값/주석 여부/export를 함께 유지한다.
            record = replace(record, value=current.value, comment=current.comment,
                             export=current.export)
        output.append(record.render())

    extras = [record for key, record in existing_values.items() if key not in template_values]
    merged = "".join(output)
    if extras:
        newline = "\r\n" if "\r\n" in example else "\n"
        if merged and not merged.endswith("\n"):
            merged += newline
        if merged and not merged.endswith(newline * 2):
            merged += newline
        merged += f"# {Path(example_label).name}에 없는 기존 항목 (값 보존){newline}"
        for record in extras:
            merged += replace(record, newline=newline).render()

    # 주석 예시와 설정 키가 섞여 있어도 저장 후 선택되는 값이 달라지면 안 된다.
    merged_values = select_assignments(parse(merged, "갱신 결과"), "갱신 결과")
    for key, current in existing_values.items():
        result = merged_values[key]
        if (result.value, bool(result.comment), result.export) != (
            current.value, bool(current.comment), current.export
        ):
            raise ValueError(f"{key}: 템플릿의 중복 항목 때문에 기존 값을 보존할 수 없습니다")
    return merged


def prepare_update(example_path: Path, env_path: Path) -> EnvUpdate:
    """쓰기 전에 입력을 읽고 값이 보존된 갱신 결과를 준비한다."""
    example_path = example_path.resolve()
    env_path = env_path.resolve()
    if example_path == env_path or (env_path.exists() and env_path.samefile(example_path)):
        raise ValueError("템플릿과 갱신할 파일은 서로 달라야 합니다")

    example = example_path.read_bytes().decode("utf-8-sig")
    previous = env_path.read_bytes() if env_path.exists() else None
    existing = previous.decode("utf-8-sig") if previous is not None else ""
    content = merge_env(
        example,
        existing,
        example_label=str(example_path),
        env_label=str(env_path),
    ).encode("utf-8")
    return EnvUpdate(path=env_path, previous=previous, content=content)


def write_env(path: Path, content: bytes, previous: bytes | None) -> Path | None:
    """백업은 매번 새로 만들고, 완성된 임시 파일로 원본을 교체한다."""
    mode = stat.S_IMODE(path.stat().st_mode) if previous is not None else 0o600
    backup_path = None
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=f"{path.name}.sync.",
                                         delete=False) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(content)
        temporary_path.chmod(mode)

        if previous is not None:
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=f"{path.name}.bak.",
                                             delete=False) as backup:
                backup_path = Path(backup.name)
                backup.write(previous)

        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return backup_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--example", type=Path,
                        help="단일 파일 처리에 사용할 템플릿 (생략 시 루트 .env.example)")
    parser.add_argument("--env", type=Path,
                        help="단일 파일 처리 대상 (생략 시 루트 .env)")
    parser.add_argument("--dry-run", action="store_true",
                        help="파일이나 백업을 만들지 않고 변경 필요 여부만 확인")
    args = parser.parse_args(argv)

    if args.example is not None or args.env is not None:
        pairs = [(args.example or PROJECT_ROOT / ".env.example",
                  args.env or PROJECT_ROOT / ".env")]
    else:
        pairs = [(PROJECT_ROOT / f"{name}.example", PROJECT_ROOT / name)
                 for name in (".env", ".env.secret")]

    try:
        # 두 번째 파일의 형식 오류로 첫 번째 파일만 갱신되는 일을 방지한다.
        updates = [prepare_update(example, env) for example, env in pairs]
        for update in updates:
            if update.content == update.previous:
                print(f"변경 없음: {update.path}")
            elif args.dry_run:
                print(f"변경 필요 (파일 변경 없음): {update.path}")
            else:
                backup = write_env(update.path, update.content, update.previous)
                print(f"양식 적용 완료: {update.path}")
                if backup is not None:
                    print(f"기존 파일 백업: {backup}")
    except UnicodeError:
        print("오류: UTF-8 형식의 파일만 지원합니다.", file=sys.stderr)
        return 1
    except (OSError, ValueError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
