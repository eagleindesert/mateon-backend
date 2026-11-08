"""Read request bodies for the stdlib HTTP stubs, including streaming clients."""


def read_http_body(handler):
    def read_exact(size):
        data = handler.rfile.read(size)
        if len(data) != size:
            raise ValueError('Incomplete request body')
        return data

    transfer_encoding = handler.headers.get('Transfer-Encoding', '').strip().lower()
    if transfer_encoding:
        if transfer_encoding != 'chunked':
            raise ValueError('Unsupported Transfer-Encoding')
        chunks = []
        while True:
            line = handler.rfile.readline()
            if not line.endswith(b'\r\n'):
                raise ValueError('Invalid chunk size line')
            size_text = line[:-2].split(b';', 1)[0].strip()
            if not size_text or any(c not in b'0123456789abcdefABCDEF' for c in size_text):
                raise ValueError('Invalid chunk size')
            size = int(size_text, 16)
            if size == 0:
                # Consume optional trailer headers and the terminating empty line.
                while True:
                    trailer = handler.rfile.readline()
                    if not trailer.endswith(b'\r\n'):
                        raise ValueError('Invalid chunk trailer')
                    if trailer == b'\r\n':
                        return b''.join(chunks)
            chunks.append(read_exact(size))
            if read_exact(2) != b'\r\n':
                raise ValueError('Invalid chunk terminator')

    size = int(handler.headers.get('Content-Length', '0'))
    if size < 0:
        raise ValueError('Invalid Content-Length')
    return read_exact(size)
