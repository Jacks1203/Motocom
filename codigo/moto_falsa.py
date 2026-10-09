#!/usr/bin/env python3
"""Hace de moto contra el móvil, por el cable, para probar la pantalla de la moto sin la moto.

En el móvil: Ajustes > Modo moto > Con Carbit Ride > Conectar. Después, aquí:

    for p in 10920 10921 10922; do adb forward tcp:$((p+10000)) tcp:$p; done
    python3 herramientas/moto_falsa.py salida.h264 [segundos, 4 si no se dice]
    ffmpeg -i salida.h264 -frames:v 1 imagen.png

Dice lo que dijo la moto de verdad el 30-09-2026 y guarda el vídeo que manda el móvil.
"""
import socket, struct, json, time, sys
def con(p):
    s=socket.create_connection(("127.0.0.1",10000+p)); s.settimeout(5); return s
def rd(s,n):
    b=b""
    while len(b)<n:
        c=s.recv(n-len(b))
        if not c: raise EOFError
        b+=c
    return b
def pxc_w(s,cmd,body=b""):
    l=16+len(body); s.sendall(struct.pack("<IIII",cmd,l,cmd^l,0)+body)
def pxc_r(s):
    cmd,l,x,_=struct.unpack("<IIII",rd(s,16)); assert cmd^l==x; return cmd,rd(s,l-16)
def rv_w(s,cmd,body=b""): s.sendall(struct.pack("<HHI",cmd,len(body),0)+body)
def rv_r(s):
    cmd,l,_=struct.unpack("<HHI",rd(s,8)); return cmd,rd(s,l)
a=con(10922); pxc_w(a,0x10000); print("abre C2P ->",hex(pxc_r(a)[0]))
info={"sdkVersion":"1.0.14","pxcVersion":"1.0.2","uuid":"XXXX","HUID":"XXXXXXXXXXXX","supportFunction":128,"channel":"37512","flavor":51,"socketTimeoutPeriodWifi":9,"supportSyncCorrectTime":True}
pxc_w(a,0x10010,json.dumps(info).encode()); c,b=pxc_r(a); print(hex(c),b.decode()[:400])
signed=json.loads(b)["encryptedHUID"]
p=con(10922); pxc_w(p,0x20000); print("abre P2C ->",hex(pxc_r(p)[0]))
c,b=pxc_r(p); print("movil pregunta",hex(c),b.decode()); pxc_w(p,c+1)
pxc_w(a,0x10450); c,b=pxc_r(a); print("hora",hex(c),b.decode())
pxc_w(a,0x103E0,b'{"sn":"8DVLX36WDMMNHVP3TWGR2"}'); print("sn ->",hex(pxc_r(a)[0]))
for _ in range(3):
    c,b=pxc_r(p); pxc_w(p,c+1)
    if c==0x201C0: print("veredicto",b.decode())
k=con(10921)
rv_w(k,0x10,struct.pack("<hhiiihhi8x",592,816,0,2,2,0,0,4194304)+signed.encode()); c,b=rv_r(k); print("config ->",hex(c),struct.unpack("<ihh",b))
rv_w(k,0x40); print("latido ->",hex(rv_r(k)[0]))
rv_w(k,0x70); c,b=rv_r(k); print("empezar ->",hex(c))
d=con(10920); out=open(sys.argv[1],"wb"); n=0; z=0; sizes=[]; t0=time.time()
dura=float(sys.argv[2]) if len(sys.argv)>2 else 4
while time.time()-t0<dura:
    rv_w(d,0x72); l=struct.unpack("<i",rd(d,4))[0]
    if l: out.write(rd(d,l)); n+=1; sizes.append(l)
    else: z+=1
    if int(time.time()-t0)!=int(time.time()-t0-0.03): rv_w(k,0x40); rv_r(k); pxc_w(a,0x70000000); pxc_r(a)
print("tramas",n,"vacias",z,"primeras",sizes[:8])
