import socket, select, json, hashlib, time
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
K = hashlib.md5(b"yGAdlopoPVldABfn").digest()
def dec(d):
    i, j = d.find(b"{"), d.rfind(b"}")
    if 0 <= i < j:
        try: return json.loads(d[i:j+1])
        except ValueError: pass
    try:
        c = Cipher(algorithms.AES(K), modes.ECB()).decryptor()
        p = c.update(d[20:-8]) + c.finalize()
        return json.loads(p[:-p[-1]].decode())
    except Exception: return None
socks = []
for porta in (6666, 6667):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", porta)); socks.append(s)
conta = {}
t0 = time.time()
print("  30 secondi di ascolto...")
while time.time() - t0 < 30:
    r, _, _ = select.select(socks, [], [], 1)
    for s in r:
        d, a = s.recvfrom(4096)
        m = dec(d)
        if m and "gwId" in m:
            k = m["gwId"]
            conta[k] = conta.get(k, 0) + 1
            if k == "bf4f34bcb52162cd7aukex":
                print(f"    +{time.time()-t0:4.1f}s  POMPA si e' annunciata da {m.get('ip')}")
print()
for k, n in sorted(conta.items(), key=lambda x: -x[1]):
    marca = "  <-- POMPA" if k == "bf4f34bcb52162cd7aukex" else ""
    print(f"  {k}  {n} annunci{marca}")
if "bf4f34bcb52162cd7aukex" not in conta:
    print("  la POMPA non si e' annunciata nemmeno una volta: e' spenta o fuori portata Wi-Fi")
