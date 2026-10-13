package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fe(p string, size int64) FileEntry { return FileEntry{Path: p, Size: size} }

func byTitle(games []*Game) map[string]*Game {
	m := map[string]*Game{}
	for _, g := range games {
		m[g.Title] = g
	}
	return m
}

func TestCueBinWithContent(t *testing.T) {
	files := []FileEntry{
		fe("psx/Crash (USA).cue", 1), fe("psx/Crash (USA) (Track 1).bin", 100), fe("psx/Crash (USA) (Track 2).bin", 50),
		fe("psx/Spyro (USA).chd", 300),
	}
	read := func(p string) []byte {
		if p == "psx/Crash (USA).cue" {
			return []byte("FILE \"Crash (USA) (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"Crash (USA) (Track 2).bin\" BINARY\n")
		}
		return nil
	}
	games := groupSystem("psx", files, read)
	if len(games) != 2 {
		t.Fatalf("esperaba 2 juegos, hay %d", len(games))
	}
	m := byTitle(games)
	if g := m["Crash"]; g == nil || len(g.Files) != 3 || g.Size != 151 || g.Kind != "multi" {
		t.Fatalf("Crash mal agrupado: %+v", g)
	}
	if g := m["Spyro"]; g == nil || g.Tags[0] != "USA" {
		t.Fatalf("Spyro: %+v", g)
	}
}

func TestCueBinByName(t *testing.T) {
	files := []FileEntry{fe("psx/Crash (USA).cue", 1), fe("psx/Crash (USA) (Track 1).bin", 100), fe("psx/Crash (USA) (Track 2).bin", 50)}
	games := groupSystem("psx", files, nil)
	if len(games) != 1 || len(games[0].Files) != 3 {
		t.Fatalf("sin contenido debería agrupar por nombre: %+v", games)
	}
}

func TestM3UAbsorbsCues(t *testing.T) {
	files := []FileEntry{
		fe("psx/FF7.m3u", 1),
		fe("psx/.hidden/FF7 (Disc 1).cue", 1), fe("psx/.hidden/FF7 (Disc 1).bin", 10),
		fe("psx/.hidden/FF7 (Disc 2).cue", 1), fe("psx/.hidden/FF7 (Disc 2).bin", 10),
	}
	read := func(p string) []byte {
		switch p {
		case "psx/FF7.m3u":
			return []byte(".hidden/FF7 (Disc 1).cue\r\n.hidden/FF7 (Disc 2).cue\r\n")
		case "psx/.hidden/FF7 (Disc 1).cue":
			return []byte(`FILE "FF7 (Disc 1).bin" BINARY`)
		case "psx/.hidden/FF7 (Disc 2).cue":
			return []byte(`FILE "FF7 (Disc 2).bin" BINARY`)
		}
		return nil
	}
	games := groupSystem("psx", files, read)
	if len(games) != 1 {
		t.Fatalf("esperaba 1 juego, hay %d: %+v", len(games), games)
	}
	if len(games[0].Files) != 5 || games[0].Size != 23 {
		t.Fatalf("FF7 incompleto: %+v", games[0])
	}
}

func TestM3UByNameWithCHD(t *testing.T) {
	files := []FileEntry{fe("ps2/Game.m3u", 1), fe("ps2/Game (Disc 1).chd", 10), fe("ps2/Game (Disc 2).chd", 10), fe("ps2/Other.chd", 5)}
	games := groupSystem("ps2", files, nil)
	if len(games) != 2 {
		t.Fatalf("esperaba 2, hay %d", len(games))
	}
}

func TestFoldersAndCategories(t *testing.T) {
	files := []FileEntry{
		fe("ps3/Demon's Souls.ps3/PS3_GAME/USRDIR/EBOOT.BIN", 10), fe("ps3/Demon's Souls.ps3/PS3_DISC.SFB", 1),
		fe("gba/Hacks/Pokemon Unbound.gba", 30), fe("gba/Hacks/Pokemon Radical Red.gba", 30),
		fe("psx/Crash Bandicoot/Crash Bandicoot.chd", 40),
	}
	all := append(groupSystem("ps3", files[:2], nil), groupSystem("gba", files[2:4], nil)...)
	all = append(all, groupSystem("psx", files[4:], nil)...)
	m := byTitle(all)
	if g := m["Demon's Souls.ps3"]; g == nil || g.Kind != "folder" || len(g.Files) != 2 {
		t.Fatalf("PS3 como carpeta: %+v", all)
	}
	if m["Pokemon Unbound"] == nil || m["Pokemon Radical Red"] == nil {
		t.Fatalf("una carpeta de categoría no es un juego: %+v", all)
	}
	if g := m["Crash Bandicoot"]; g == nil || g.Kind != "folder" {
		t.Fatalf("carpeta con el mismo nombre del juego: %+v", g)
	}
}

func TestSwitchParts(t *testing.T) {
	files := []FileEntry{
		fe("switch/Zelda TOTK [0100F2C0115B6000][v0].nsp", 16000),
		fe("switch/updates/Zelda TOTK [0100F2C0115B6800][v393216].nsp", 1000),
		fe("switch/Mario Kart 8 Deluxe [0100152000022000][v0].xci", 7000),
		fe("switch/Mario Kart 8 Deluxe [0100152000023001][DLC].nsp", 500),
		fe("switch/Mario Kart 8 Deluxe [0100152000023002][DLC].nsp", 600),
		fe("switch/Homebrew.nro", 5),
	}
	games := groupSystem("switch", files, nil)
	if len(games) != 3 {
		t.Fatalf("esperaba 3 juegos, hay %d: %+v", len(games), games)
	}
	m := byTitle(games)
	z := m["Zelda TOTK"]
	if z == nil || len(z.Parts) != 2 || z.Parts[0].Kind != "base" || z.Parts[1].Kind != "update" || z.Parts[1].Version != 393216 {
		t.Fatalf("Zelda: %+v", z)
	}
	mk := m["Mario Kart 8 Deluxe"]
	if mk == nil || len(mk.Parts) != 3 || mk.Size != 8100 || mk.Serial != "0100152000022000" {
		t.Fatalf("MK8: %+v", mk)
	}
}

func TestIgnoredFiles(t *testing.T) {
	for _, n := range []string{"Game.sav", "Game.srm", "Game.state1", "Game.ml2", "gamelist.xml", "Thumbs.db"} {
		if !isIgnoredFile(n) {
			t.Errorf("%s debería ignorarse", n)
		}
	}
	for _, n := range []string{"Game.nds", "Game.chd", "Game.iso", "Game.m3u", "Game.3ds"} {
		if isIgnoredFile(n) {
			t.Errorf("%s no debería ignorarse", n)
		}
	}
}

func TestCompare(t *testing.T) {
	snap := LibrarySnapshot{Version: 1, Games: groupSystem("switch", []FileEntry{
		fe("switch/Zelda [0100F2C0115B6000][v0].nsp", 100), fe("switch/Zelda [0100F2C0115B6800][v1].nsp", 10),
	}, nil), Bios: []FileEntry{fe("scph1001.bin", 1), fe("dc/dc_boot.bin", 1)}}
	snap.Games = append(snap.Games, groupSystem("psx", []FileEntry{fe("psx/Crash.chd", 50)}, nil)...)
	rep := DeviceReport{
		Files: []FileEntry{fe("switch/zelda [0100F2C0115B6000][v0].nsp", 100), fe("psx/Crash.chd", 49), fe("nds/Pokemon.nds", 64)},
		Bios:  []FileEntry{fe("SCPH1001.BIN", 1)},
	}
	res := compare(snap, rep)
	states := map[string]GameState{}
	for _, s := range res.States {
		states[s.ID] = s
	}
	for _, g := range snap.Games {
		s := states[g.ID]
		switch g.System {
		case "switch":
			if s.State != "partial" || len(s.Missing) != 1 || !strings.Contains(s.Missing[0], "6800") {
				t.Errorf("Zelda debería estar a medias (falta la actualización): %+v", s)
			}
		case "psx":
			if s.State != "installed" || !s.Differs {
				t.Errorf("Crash está con otro tamaño: %+v", s)
			}
		}
	}
	if len(res.TabletOnly) != 1 || res.TabletOnly[0].Title != "Pokemon" {
		t.Errorf("solo en la tablet: %+v", res.TabletOnly)
	}
	if res.BiosPresent != 1 || len(res.BiosMissing) != 1 {
		t.Errorf("BIOS: %+v", res)
	}
}

func TestScanAndSafeJoin(t *testing.T) {
	dir := t.TempDir()
	mk := func(p string, n int) {
		full := filepath.Join(dir, filepath.FromSlash(p))
		_ = os.MkdirAll(filepath.Dir(full), 0o755)
		_ = os.WriteFile(full, make([]byte, n), 0o644)
	}
	mk("psx/Crash.chd", 10)
	mk("psx/Crash.sav", 1)
	mk("gba/media/covers/x.png", 1)
	mk("gba/Mario.gba", 4)
	mk("bios/scph1001.bin", 2)
	games, systems, bios, err := scanLibrary(Config{RomsDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	if len(games) != 2 || len(systems) != 2 || len(bios) != 1 || bios[0].Path != "scph1001.bin" {
		t.Fatalf("escaneo: %d juegos %d sistemas %+v", len(games), len(systems), bios)
	}
	if _, err := safeJoin(dir, "../secreto.txt"); err == nil {
		// path.Clean deja "/secreto.txt" dentro de la carpeta: no sale, pero tampoco existe.
		p, _ := safeJoin(dir, "../secreto.txt")
		if !strings.HasPrefix(p, dir) {
			t.Fatalf("salió de la carpeta: %s", p)
		}
	}
	if _, err := safeJoin(dir, ""); err == nil {
		t.Fatal("ruta vacía debería fallar")
	}
}
