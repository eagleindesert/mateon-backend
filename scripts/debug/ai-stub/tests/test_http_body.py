import io
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'lib'))
from http_body import read_http_body


class HttpBodyTest(unittest.TestCase):
    def handler(self, headers, raw):
        return SimpleNamespace(headers=headers, rfile=io.BytesIO(raw))

    def test_content_length_uses_byte_length(self):
        body = '{"title":"공모전"}'.encode()
        handler = self.handler({'Content-Length': str(len(body))}, body + b'extra')
        self.assertEqual(read_http_body(handler), body)
        self.assertEqual(handler.rfile.read(), b'extra')

    def test_chunked_reassembles_utf8_split_across_chunks(self):
        body = '{"title":"공모전"}'.encode()
        parts = [body[:12], body[12:14], body[14:]]
        raw = b''.join(f'{len(part):X}\r\n'.encode() + part + b'\r\n' for part in parts)
        handler = self.handler({'Transfer-Encoding': 'Chunked'}, raw + b'0\r\n\r\n')
        self.assertEqual(read_http_body(handler), body)

    def test_chunk_extensions_and_trailers(self):
        handler = self.handler({'Transfer-Encoding': 'chunked'},
                               b'3;name=value\r\nabc\r\n0\r\nX-Test: yes\r\n\r\nextra')
        self.assertEqual(read_http_body(handler), b'abc')
        self.assertEqual(handler.rfile.read(), b'extra')

    def test_empty_body(self):
        self.assertEqual(read_http_body(self.handler({}, b'')), b'')
        self.assertEqual(read_http_body(self.handler({'Transfer-Encoding': 'chunked'},
                                                    b'0\r\n\r\n')), b'')

    def test_invalid_chunked_bodies_raise(self):
        for raw in (b'xyz\r\n', b'-1\r\n', b'3\r\nab', b'3\r\nabcxx',
                    b'3\nabc\r\n', b'0\r\n', b'0\r\nX-Test: yes\n'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                read_http_body(self.handler({'Transfer-Encoding': 'chunked'}, raw))

    def test_invalid_content_length_raises(self):
        for length in ('-1', 'invalid', '5'):
            with self.subTest(length=length), self.assertRaises(ValueError):
                read_http_body(self.handler({'Content-Length': length}, b'abc'))

    def test_unsupported_transfer_encoding_raises(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported Transfer-Encoding'):
            read_http_body(self.handler({'Transfer-Encoding': 'gzip, chunked'}, b''))


if __name__ == '__main__':
    unittest.main()
