#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
pdf_path=''
while (($#)); do
  case "$1" in
    --pdf-path) pdf_path=${2:?PDF 경로가 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 03_02_portfolio_summarize.sh [--pdf-path FILE]'; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
mateon_color_printf Magenta '\n########## 3-2. Portfolio PDF 요약 ##########\n'
run_tag="pdf$RANDOM$RANDOM"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
make_pdf() {
  python3 - "$1" "$2" <<'PY'
import pathlib,sys
path,text=sys.argv[1:]
text=text.replace('\\','\\\\').replace('(','\\(').replace(')','\\)')
stream=f"BT /F1 14 Tf 60 720 Td ({text}) Tj ET".encode()
parts=[b'%PDF-1.4\n']
objects=[
 b'<< /Type /Catalog /Pages 2 0 R >>',
 b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
 b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
 b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
 b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
offsets=[0]
for i,obj in enumerate(objects,1):
    offsets.append(sum(map(len,parts)))
    parts.append(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
xref=sum(map(len,parts))
parts.append(b'xref\n0 6\n0000000000 65535 f \n')
parts.extend(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
parts.append(f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
pathlib.Path(path).write_bytes(b''.join(parts))
PY
}
if [[ -n "$pdf_path" ]]; then
  [[ -f "$pdf_path" ]] || { mateon_color_printf Red '%s\n' "PDF가 없습니다: $pdf_path" >&2; exit 1; }
  pdf_file="$pdf_path"
else
  pdf_file="$temp_dir/portfolio.pdf"
  make_pdf "$pdf_file" "Portfolio $run_tag - Frontend developer, React and TypeScript"
fi
path='/api/portfolios/summarize'
invoke_api_upload --path "$path" --file-path "$pdf_file" --part-name pdf_file \
  --mime application/pdf --title '3.7.1 PDF 요약 (비인증 - 차단 기대)'
printf '\n'
if [[ -z "$(get_access_token || true)" ]]; then write_test_summary; exit $?; fi

invoke_api_upload --path "$path" --file-path "$pdf_file" --part-name pdf_file \
  --mime application/pdf --auth --title '3.7.2 PDF 요약 (첫 업로드)'
first_result=$mateon_response
printf '\n'
first="$(json_get "$first_result" data)"
if [[ -n "$first" ]]; then
  json_has_key "$first" summary && result=true || result=false
  assert_test '3.7.2 응답에 summary 필드가 있다' "$result"
  first_summary="$(json_get "$first" summary)"
  [[ -n "$first_summary" ]] && result=true || result=false
  assert_test '3.7.2 summary가 비어 있지 않다' "$result"
  json_has_key "$first" pdfId && result=false || result=true
  assert_test '3.7.2 응답에 pdfId가 실리지 않는다' "$result"

  invoke_api_upload --path "$path" --file-path "$pdf_file" --part-name pdf_file \
    --mime application/pdf --auth --title '3.7.3 같은 PDF 재업로드'
  second_summary="$(json_get "$mateon_response" data.summary)"
  printf '\n'
  [[ "$second_summary" == "$first_summary" ]] && result=true || result=false
  assert_test '3.7.3 같은 PDF는 같은 요약을 준다 (캐시)' "$result"

  other_pdf="$temp_dir/other.pdf"
  make_pdf "$other_pdf" "Completely different portfolio $run_tag - Backend engineer, Kotlin"
  invoke_api_upload --path "$path" --file-path "$other_pdf" --part-name pdf_file \
    --mime application/pdf --auth --title '3.7.3 내용이 다른 PDF 업로드'
  other_summary="$(json_get "$mateon_response" data.summary)"
  printf '\n'
  if [[ -n "$other_summary" ]]; then
    [[ "$other_summary" != "$first_summary" ]] && result=true || result=false
    assert_test '3.7.3 내용이 다른 PDF는 새로 요약된다' "$result"
  fi
fi

cp -- "$pdf_file" "$temp_dir/invalid.txt"
printf '이건 PDF가 아니라 그냥 텍스트다.\n' > "$temp_dir/fake.pdf"
truncate -s 21M "$temp_dir/huge.pdf"
invoke_api_upload --path "$path" --file-path "$temp_dir/invalid.txt" --part-name pdf_file \
  --mime text/plain --auth --title '3.7.4 txt 업로드 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$temp_dir/fake.pdf" --part-name pdf_file \
  --mime application/pdf --auth --title '3.7.4 확장자만 .pdf인 파일 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$pdf_file" --part-name file \
  --mime application/pdf --auth --title '3.7.4 파트 이름 오타 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$temp_dir/huge.pdf" --part-name pdf_file \
  --mime application/pdf --auth --title '3.7.4 20MB 초과 업로드 (차단 기대)'
printf '\n'
write_test_summary
