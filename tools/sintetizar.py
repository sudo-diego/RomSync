"""
Genera la música de fondo y los sonidos de RomSync (todo original, sintetizado aquí: sin muestras de nadie).
Uso: python tools/sintetizar.py  →  escribe app/src/main/res/raw/*.wav
Música: bossa nova alegre a 112 BPM en Fa mayor, 16 compases que se repiten sin corte (la cola se suma al principio).
"""
import math, random, struct, wave, os
SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')
random.seed(7)

def note(n):  # número MIDI → Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def write(name, buf, peak=0.85):
    m = max(1e-9, max(abs(x) for x in buf))
    g = peak / m
    with wave.open(os.path.join(OUT, name), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, x * g)) * 32767)) for x in buf))
    print(name, '%.1f s' % (len(buf) / SR))

def add(buf, start, samples):
    for i, s in enumerate(samples):
        j = start + i
        if j >= len(buf): j -= len(buf)  # se envuelve: así el final empalma con el principio
        buf[j] += s

def epiano(f, dur, vol):  # piano eléctrico suave: seno + armónico con trémolo y caída
    n = int(dur * SR); out = []
    for i in range(n):
        t = i / SR
        env = math.exp(-2.2 * t) * min(1, t * 200)
        out.append(vol * env * (math.sin(2*math.pi*f*t) + 0.35*math.sin(2*math.pi*2*f*t)*math.exp(-6*t)
                               + 0.12*math.sin(2*math.pi*3*f*t)) * (1 + 0.08*math.sin(2*math.pi*5*t)))
    return out

def bass(f, dur, vol):
    n = int(dur * SR); out = []
    for i in range(n):
        t = i / SR
        env = min(1, t * 300) * math.exp(-3.0 * t)
        out.append(vol * env * (math.sin(2*math.pi*f*t) + 0.25*math.sin(2*math.pi*2*f*t)))
    return out

def marimba(f, dur, vol):
    n = int(dur * SR); out = []
    for i in range(n):
        t = i / SR
        env = min(1, t * 400) * math.exp(-7.5 * t)
        out.append(vol * env * (math.sin(2*math.pi*f*t) + 0.18*math.sin(2*math.pi*4*f*t)*math.exp(-20*t)))
    return out

def shaker(dur, vol):
    n = int(dur * SR); out = []; prev = 0
    for i in range(n):
        t = i / SR
        x = random.uniform(-1, 1); hp = x - prev; prev = x
        out.append(vol * hp * math.exp(-60 * t))
    return out

def rim(vol):
    n = int(0.05 * SR); return [vol * math.sin(2*math.pi*1700*i/SR) * math.exp(-90*i/SR) for i in range(n)]

def music():
    bpm = 112; beat = 60 / bpm; bars = 16
    total = int(bars * 4 * beat * SR)
    buf = [0.0] * total
    # Acordes (MIDI) por compás: Fmaj7 | Em7 A7 | Dm7 | G7 | Gm7 | C7 | Fmaj7 | Bbmaj7 ... (dos vueltas con variación)
    prog = [
        ([53,57,60,64], 41), ([52,55,59,62], 40), ([50,53,57,60], 38), ([55,59,62,65], 43),
        ([55,58,62,65], 43), ([48,52,55,58], 36), ([53,57,60,64], 41), ([58,62,65,69], 46),
        ([53,57,60,64], 41), ([57,61,64,67], 45), ([50,53,57,60], 38), ([55,59,62,65], 43),
        ([55,58,62,65], 43), ([48,52,55,58], 36), ([53,57,60,64], 41), ([48,52,55,58], 36),
    ]
    # Ritmo de bossa para el piano (en corcheas de 8 por compás): golpes "sincopados"
    comp = [0, 3, 5, 6]
    # Melodía original (MIDI, inicio en corcheas, duración en corcheas), dos frases que preguntan y responden
    mel = [
        (72,0,2),(74,2,1),(76,3,3),(74,6,2),(72,8,1),(69,9,2),(67,11,3),(69,14,2),
        (70,16,2),(72,18,1),(74,19,3),(72,22,2),(70,24,1),(67,25,2),(65,27,5),
        (72,32,2),(74,34,1),(76,35,3),(79,38,2),(77,40,1),(76,41,2),(74,43,3),(72,46,2),
        (70,48,2),(69,50,1),(67,51,3),(65,54,2),(67,56,1),(69,57,2),(65,59,5),
    ]
    eighth = beat / 2
    for b, (ch, root) in enumerate(prog):
        s0 = int(b * 4 * beat * SR)
        for k in comp:
            for n in ch:
                add(buf, s0 + int(k * eighth * SR), epiano(note(n), 0.9, 0.045))
        # bajo: raíz en 1, quinta en 3 (patrón de bossa)
        add(buf, s0, bass(note(root), 1.2, 0.26))
        add(buf, s0 + int(3 * eighth * SR), bass(note(root + 7), 0.5, 0.18))
        add(buf, s0 + int(4 * eighth * SR), bass(note(root + 7), 1.0, 0.22))
        add(buf, s0 + int(7 * eighth * SR), bass(note(root), 0.5, 0.18))
        for k in range(8):  # shaker en corcheas, acentos suaves
            add(buf, s0 + int(k * eighth * SR), shaker(0.08, 0.05 if k % 2 else 0.08))
        for k in (0, 3, 6):  # aro de caja (clave de bossa simplificada)
            add(buf, s0 + int((k + (0 if b % 2 == 0 else 1)) * eighth * SR), rim(0.06))
    for rep in range(2):  # la melodía suena dos veces (compases 0-7 y 8-15), la segunda una octava más arriba
        for n, st, du in mel:
            add(buf, int((rep * 64 + st) * eighth * SR), marimba(note(n + 12 * rep), min(du * eighth + 0.3, 1.4), 0.16))
    # suavizar un poco los agudos (filtro de un polo)
    out = []; y = 0
    for x in buf:
        y += 0.55 * (x - y); out.append(y)
    write('musica_tienda.wav', out, 0.6)

def sfx():
    def blip(freqs, step, dur, vol=1.0, decay=40):
        n = int((step * (len(freqs) - 1) + dur) * SR); buf = [0.0] * n
        for k, f in enumerate(freqs):
            s0 = int(k * step * SR)
            for i in range(int(dur * SR)):
                t = i / SR
                buf[s0 + i] += vol * min(1, t * 800) * math.exp(-decay * t) * (math.sin(2*math.pi*f*t) + 0.3*math.sin(2*math.pi*2*f*t))
        return buf
    write('sfx_mover.wav', blip([note(88)], 0, 0.05, decay=90), 0.45)          # tocar / moverse: un tic agudo
    write('sfx_abrir.wav', blip([note(79), note(86)], 0.06, 0.16, decay=28), 0.6)   # abrir: dos notas que suben
    write('sfx_volver.wav', blip([note(84), note(77)], 0.06, 0.14, decay=30), 0.55)  # volver: dos notas que bajan
    write('sfx_listo.wav', blip([note(76), note(79), note(84), note(88)], 0.07, 0.3, decay=14), 0.6)  # terminó algo
    write('sfx_aviso.wav', blip([note(57), note(55)], 0.09, 0.18, decay=18), 0.5)   # error o cuidado (grave, suave)
    write('sfx_interruptor.wav', blip([note(81)], 0, 0.07, decay=60), 0.5)          # interruptor / casilla

if __name__ == '__main__':
    sfx(); music()
