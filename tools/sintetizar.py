"""
Genera la música de fondo y los sonidos de RomSync. Todo es ORIGINAL y sintetizado aquí (sin muestras de nadie).
Uso: python tools/sintetizar.py   (necesita ffmpeg en el PATH para comprimir la música a .ogg)
Escribe app/src/main/res/raw/sfx_<juego>_<sonido>.wav (3 juegos de sonidos). Las funciones de música quedan por si
se quieren volver a usar, pero la app ya no trae música: cada usuario elige su canción.
"""
import math, random, struct, wave, os, subprocess, tempfile

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')
TAU = 2 * math.pi

def hz(n): return 440.0 * 2 ** ((n - 69) / 12)

def save_wav(path, buf, peak):
    m = max(1e-9, max(abs(x) for x in buf)); g = peak / m
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, x * g)) * 32767)) for x in buf))

def save_ogg(name, buf, peak=0.7):
    tmp = os.path.join(tempfile.gettempdir(), name + '.wav')
    save_wav(tmp, buf, peak)
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', tmp, '-c:a', 'libvorbis', '-q:a', '3',
                    os.path.join(OUT, name + '.ogg')], check=True)
    os.remove(tmp)
    print(name, '%.0f s' % (len(buf) / SR))

class Song:
    def __init__(self, seconds): self.buf = [0.0] * int(seconds * SR)
    def add(self, t, samples):
        s0 = int(t * SR); n = len(self.buf)
        for i, v in enumerate(samples):
            self.buf[(s0 + i) % n] += v  # se envuelve: la cola suena al principio y el loop no corta

# ---------- instrumentos ----------
def env_ad(t, a, d): return min(1, t / a) * math.exp(-d * t)

def epiano(f, dur, vol):
    return [vol * env_ad(i / SR, .004, 2.0) * (math.sin(TAU*f*i/SR) + .35*math.sin(TAU*2*f*i/SR)*math.exp(-6*i/SR)
            + .1*math.sin(TAU*3*f*i/SR)) * (1 + .08*math.sin(TAU*5*i/SR)) for i in range(int(dur*SR))]

def marimba(f, dur, vol):
    return [vol * env_ad(i/SR, .002, 7) * (math.sin(TAU*f*i/SR) + .2*math.sin(TAU*4*f*i/SR)*math.exp(-25*i/SR)) for i in range(int(dur*SR))]

def vibes(f, dur, vol):
    return [vol * env_ad(i/SR, .003, 2.6) * (math.sin(TAU*f*i/SR) + .25*math.sin(TAU*4.0*f*i/SR)*math.exp(-8*i/SR))
            * (1 + .25*math.sin(TAU*6*i/SR)) for i in range(int(dur*SR))]

def flute(f, dur, vol):
    n = int(dur*SR); out = []
    for i in range(n):
        t = i/SR; e = min(1, t/.06) * min(1, (dur - t)/.08) if dur - t > 0 else 0
        vib = 1 + .004*math.sin(TAU*5.2*t) * min(1, t/.3)
        out.append(vol * e * (math.sin(TAU*f*vib*t) + .18*math.sin(TAU*2*f*vib*t) + .02*random.uniform(-1, 1)))
    return out

def bass(f, dur, vol):
    return [vol * env_ad(i/SR, .003, 2.8) * (math.sin(TAU*f*i/SR) + .25*math.sin(TAU*2*f*i/SR)) for i in range(int(dur*SR))]

def square(f, dur, vol, duty=.5, decay=3.0):
    return [vol * env_ad(i/SR, .002, decay) * (1 if (f*i/SR) % 1 < duty else -1) for i in range(int(dur*SR))]

def triangle(f, dur, vol):
    return [vol * env_ad(i/SR, .002, 1.5) * (4*abs((f*i/SR) % 1 - .5) - 1) for i in range(int(dur*SR))]

def noise(dur, vol, decay=60, hp=True):
    out = []; prev = 0
    for i in range(int(dur*SR)):
        x = random.uniform(-1, 1); y = x - prev if hp else x; prev = x
        out.append(vol * y * math.exp(-decay * i/SR))
    return out

def kick(vol):
    return [vol * math.sin(TAU * (50 + 90*math.exp(-30*i/SR)) * i/SR) * math.exp(-9*i/SR) for i in range(int(.35*SR))]

def rim(vol): return [vol * math.sin(TAU*1700*i/SR) * math.exp(-90*i/SR) for i in range(int(.05*SR))]

def lowpass(buf, a):
    out = []; y = 0
    for x in buf: y += a * (x - y); out.append(y)
    return out

# ---------- composición ----------
def melody_line(rng, scale, chord, length8, start_note, rest_p=.2):
    """Una frase improvisada: notas de la escala, cae en notas del acorde en los tiempos fuertes."""
    notes = []; n = start_note; pos = 0
    while pos < length8:
        du = rng.choice([1, 1, 2, 2, 3])
        if rng.random() < rest_p and pos % 4 != 0: pos += du; continue
        if pos % 4 == 0:  # tiempo fuerte → nota del acorde más cercana
            n = min((c + 12*o for c in chord for o in range(-1, 3)), key=lambda c: abs(c - n))
        else:
            step = rng.choice([-2, -1, -1, 1, 1, 2, 3])
            cands = sorted(set(s + 12*o for s in scale for o in range(4, 8)))
            idx = min(range(len(cands)), key=lambda i: abs(cands[i] - n))
            n = cands[max(0, min(len(cands)-1, idx + step))]
        notes.append((n, pos, du)); pos += du
    return notes

def bossa():
    rng = random.Random(11); bpm = 112; beat = 60/bpm; e8 = beat/2; bar = 4*beat
    I = [([53,57,60,64],41), ([52,55,59,62],40), ([50,53,57,60],38), ([55,59,62,65],43)]
    II = [([55,58,62,65],43), ([48,52,55,58],36), ([53,57,60,64],41), ([58,62,65,69],46)]
    B = [([50,53,57,60],38), ([46,50,53,57],46), ([55,58,62,65],43), ([48,52,55,58],36),
         ([45,49,52,55],45), ([50,53,57,60],38), ([43,47,50,53],43), ([48,52,55,58],36)]
    form = [('intro', I), ('A', I+II), ('A2', I+II), ('B', B), ('A3', I+II), ('pausa', II), ('solo', I+II), ('B2', B), ('A', I+II)]
    bars = sum(len(p) for _, p in form)
    s = Song(bars * bar)
    motif = [(72,0,2),(74,2,1),(76,3,3),(74,6,2),(72,8,1),(69,9,2),(67,11,3),(69,14,2),
             (70,16,2),(72,18,1),(74,19,3),(72,22,2),(70,24,1),(67,25,2),(65,27,5),
             (72,32,2),(74,34,1),(76,35,3),(79,38,2),(77,40,1),(76,41,2),(74,43,3),(72,46,2),
             (70,48,2),(69,50,1),(67,51,3),(65,54,2),(67,56,1),(69,57,2),(65,59,5)]
    bmel = [(69,0,3),(72,3,2),(74,5,3),(77,8,4),(76,12,2),(74,14,2),(70,16,3),(69,19,2),(67,21,3),(65,24,6),(64,30,2),
            (69,32,3),(72,35,2),(76,37,3),(79,40,4),(77,44,2),(76,46,2),(74,48,3),(72,51,2),(71,53,3),(72,56,8)]
    scale = [65,67,69,70,72,74,76]
    t0 = 0
    for name, prog in form:
        for b, (ch, root) in enumerate(prog):
            st = t0 + b*bar
            soft = name in ('intro', 'pausa')
            for k in [0, 3, 5, 6]:
                for n in ch: s.add(st + k*e8, epiano(hz(n), .9, .03 if soft else .042))
            if name != 'intro':
                s.add(st, bass(hz(root), 1.2, .24)); s.add(st+3*e8, bass(hz(root+7), .5, .16))
                s.add(st+4*e8, bass(hz(root+7), 1, .2)); s.add(st+7*e8, bass(hz(root), .5, .16))
            for k in range(8): s.add(st+k*e8, noise(.07, .05 if k % 2 else .08))
            if not soft:
                for k in (0, 3, 6): s.add(st + (k + b % 2)*e8, rim(.06))
            if name in ('A2', 'A3', 'solo', 'B2') and b % 4 == 3:  # golpe de bombo al cerrar cada frase
                s.add(st + 7*e8, kick(.25))
        L = len(prog)*8
        if name in ('A', 'A2', 'A3'):
            up = 12 if name == 'A3' else 0
            inst = marimba if name != 'A2' else flute
            for n, p, du in motif:
                if p < L: s.add(t0 + p*e8, inst(hz(n+up), min(du*e8 + .3, 1.6), .16 if inst is marimba else .07))
            if name == 'A3':  # contracanto con vibráfono
                for n, p, du in motif[::2]:
                    s.add(t0 + (p+1)*e8, vibes(hz(n-5), du*e8 + .4, .06))
        elif name in ('B', 'B2'):
            for n, p, du in bmel:
                s.add(t0 + p*e8, (flute if name == 'B' else vibes)(hz(n + (0 if name == 'B' else 12)), du*e8 + .2, .07 if name == 'B' else .08))
        elif name == 'solo':
            for i in range(0, L, 16):
                ch = prog[i//8][0]
                for n, p, du in melody_line(rng, scale, ch, 16, 74):
                    s.add(t0 + (i+p)*e8, vibes(hz(n), du*e8 + .3, .09))
        t0 += len(prog)*bar
    save_ogg('musica_bossa', lowpass(s.buf, .55))

def lofi():
    rng = random.Random(5); bpm = 82; beat = 60/bpm; e8 = beat/2; bar = 4*beat
    A = [([51,55,58,62],39), ([48,51,55,58],36), ([44,48,51,55],44), ([46,50,53,56],46)]
    B = [([41,44,48,51],41), ([46,50,53,56],46), ([51,55,58,62],39), ([49,53,56,60],49)]
    form = [('A', A*2), ('A', A*2), ('B', B*2), ('A', A*2), ('C', B+A)]
    bars = sum(len(p) for _, p in form); s = Song(bars*bar)
    scale = [63,65,67,68,70,72,74]
    t0 = 0
    for si, (name, prog) in enumerate(form):
        for b, (ch, root) in enumerate(prog):
            st = t0 + b*bar
            for k, n in enumerate(ch):  # acorde arpegiado suave, con "swing"
                s.add(st + k*.03, epiano(hz(n), 2.2, .04))
            s.add(st + 5*e8 + .04, epiano(hz(ch[-1]+12), .9, .025))
            s.add(st, bass(hz(root-12), 1.5, .28)); s.add(st + 5*e8, bass(hz(root-5), .8, .2))
            s.add(st, kick(.35)); s.add(st + 2.6*e8 + .03, kick(.22)); s.add(st + 5*e8, kick(.3))
            s.add(st + 2*e8, noise(.15, .09, 25)); s.add(st + 6*e8, noise(.15, .09, 25))  # "caja" de ruido
            for k in range(8): s.add(st + k*e8 + (.04 if k % 2 else 0), noise(.04, .035, 90))
        L = len(prog)*8
        if name != 'A' or si > 0:
            for i in range(0, L, 16):
                ch = prog[i//8][0]
                inst = vibes if name == 'C' else marimba
                for n, p, du in melody_line(rng, scale, ch, 16, 75 if name != 'B' else 79, .35):
                    s.add(t0 + (i+p)*e8 + .02, inst(hz(n), du*e8 + .4, .08))
        t0 += len(prog)*bar
    for i in range(0, len(s.buf), 97):  # crepitar de vinilo
        if random.random() < .02: s.buf[i] += random.uniform(-.03, .03)
    save_ogg('musica_lofi', lowpass(s.buf, .35))

def retro():
    rng = random.Random(3); bpm = 138; beat = 60/bpm; e16 = beat/4; bar = 4*beat
    A = [([60,64,67],48), ([57,60,64],45), ([53,57,60],41), ([55,59,62],43)]
    B = [([53,57,60],41), ([55,59,62],43), ([52,55,59],40), ([57,60,64],45)]
    form = [('A', A*2), ('A2', A*2), ('B', B*2), ('A', A*2), ('C', B*2)]
    bars = sum(len(p) for _, p in form); s = Song(bars*bar)
    scale = [60,62,64,65,67,69,71]
    lead = [(72,0,2),(76,2,2),(79,4,4),(77,8,2),(76,10,2),(74,12,4),(72,16,2),(74,18,2),(76,20,2),(72,22,2),(69,24,8),
            (71,32,2),(72,34,2),(74,36,4),(76,40,2),(74,42,2),(72,44,4),(71,48,4),(67,52,4),(72,56,8)]
    t0 = 0
    for name, prog in form:
        for b, (ch, root) in enumerate(prog):
            st = t0 + b*bar
            for k in range(16):  # arpegio rápido (lo típico de las consolas viejas)
                s.add(st + k*e16, square(hz(ch[k % 3] + 12), e16*.9, .028, .25, 10))
            for k in range(0, 16, 2): s.add(st + k*e16, triangle(hz(root - 12 + (7 if k % 8 == 4 else 0)), e16*1.8, .2))
            for k in (0, 6, 8): s.add(st + k*e16, kick(.25))
            for k in (4, 12): s.add(st + k*e16, noise(.12, .12, 30, False))
            for k in range(0, 16, 2): s.add(st + k*e16, noise(.03, .04, 120))
        L = len(prog)*16
        if name in ('A', 'A2'):
            for rep in range(L // 64):
                for n, p, du in lead:
                    s.add(t0 + (rep*64 + p)*e16, square(hz(n + (12 if name == 'A2' else 0)), du*e16*.95, .05, .5 if name == 'A' else .125, 2))
        else:
            for i in range(0, L, 32):
                ch = prog[i//16][0]
                for n, p, du in melody_line(rng, scale, ch, 16, 76, .15):
                    s.add(t0 + (i + p*2)*e16, square(hz(n), du*2*e16*.9, .045, .25, 2.5))
        t0 += len(prog)*bar
    save_ogg('musica_retro', s.buf, .6)

# ---------- sonidos ----------
def sfx():
    def tone(f, dur, decay, kind):
        out = []
        for i in range(int(dur*SR)):
            t = i/SR; e = min(1, t*800) * math.exp(-decay*t)
            if kind == 'clasico': v = math.sin(TAU*f*t) + .3*math.sin(TAU*2*f*t)
            elif kind == 'suave': v = math.sin(TAU*f*t) + .08*math.sin(TAU*3*f*t)
            else: v = (1 if (f*t) % 1 < .5 else -1) * .6
            out.append(e * v)
        return out
    def seq(notes, step, dur, decay, kind):
        n = int((step*(len(notes)-1) + dur)*SR); buf = [0.0]*n
        for k, m in enumerate(notes):
            for i, v in enumerate(tone(hz(m), dur, decay, kind)): buf[int(k*step*SR) + i] += v
        return buf
    packs = {'clasico': 0, 'suave': -12, 'retro': 0}
    for kind, shift in packs.items():
        slow = 1.4 if kind == 'suave' else 1.0
        save = lambda name, buf, peak: save_wav(os.path.join(OUT, 'sfx_%s_%s.wav' % (kind, name)), buf, peak)
        save('mover', seq([88+shift], 0, .05*slow, 90/slow, kind), .4)
        save('abrir', seq([79+shift, 86+shift], .06, .16*slow, 28/slow, kind), .55)
        save('volver', seq([84+shift, 77+shift], .06, .14*slow, 30/slow, kind), .5)
        save('listo', seq([76+shift, 79+shift, 84+shift, 88+shift], .07, .3*slow, 14/slow, kind), .55)
        save('aviso', seq([57+shift, 55+shift], .09, .18*slow, 18/slow, kind), .5)
        save('interruptor', seq([81+shift], 0, .07*slow, 60/slow, kind), .45)
        # Descarga terminada: arpegio que sube y un acorde final que queda sonando (~1,2 s).
        j = seq([72+shift, 76+shift, 79+shift, 84+shift], .09, .5*slow, 9/slow, kind)
        chord = [0.0]*int(.9*slow*SR)
        for m in (84+shift, 88+shift, 91+shift):
            for i, v in enumerate(tone(hz(m), .9*slow, 4.5/slow, kind)): chord[i] += v * .55
        start = int(.36*SR); j = j + [0.0]*max(0, start + len(chord) - len(j))
        for i, v in enumerate(chord): j[start + i] += v
        save('jingle', j, .6)
        # 3DS: cubitos que caen. Medido en la grabación de la eShop (segundos 30,7 a 31,6): tics cortos y brillantes,
        # como de xilófono, que alternan dos notas a una cuarta (~1230 y ~1660 Hz) y se apagan en ~40 ms.
        def tic(f, n=int(.09*SR)):
            out = []
            for i in range(n):
                t = i/SR
                env = (1 - math.exp(-t*2500)) * math.exp(-t*(55 if kind != 'suave' else 40))
                v = math.sin(TAU*f*t) + 0.18*math.sin(TAU*2*f*t)*math.exp(-t*90)
                if kind == 'retro': v = (1 if math.sin(TAU*f*t) > 0 else -1)*0.5 + 0.5*v
                out.append(v*env)
            return out
        # La tanda entera en un archivo: 4 tics cada 0,21 s (lo mismo que se ve caer), alternando las dos notas. Tocar
        # cuatro sonidos cortos seguidos en la tablet sonaba como dos. Al subir, el orden de las notas va al revés.
        def tanda(notas):
            out = [0.0] * int((3 * .21 + .12) * SR)
            for k, f in enumerate(notas):
                t0 = int(k * .21 * SR)
                for i, v in enumerate(tic(f)): out[t0 + i] += v
            return out
        cubo = tanda([1230, 1640, 1230, 1640])
        cubo_sube = tanda([1640, 1230, 1640, 1230])
        save('cubo', cubo, .5)
        save('cubo_sube', cubo_sube, .5)
        riel = [0.0]*int(.22*SR)
        for k in range(3):
            for i in range(int(.025*SR)):
                t = i/SR; riel[int(k*.07*SR)+i] += math.sin(TAU*(2200 if kind != 'suave' else 1400)*t) * math.exp(-t*160)
        save('riel', riel, .35)
    print('sonidos: 3 juegos')

if __name__ == '__main__':
    for f in os.listdir(OUT):
        if f.startswith('sfx_'): os.remove(os.path.join(OUT, f))
    sfx()  # la música la elige cada usuario (Ajustes → Sonido); aquí solo los sonidos
