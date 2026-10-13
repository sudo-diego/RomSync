package main

import (
	"crypto/sha1"
	"encoding/hex"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// FileEntry es un archivo de la biblioteca, con la ruta relativa a la carpeta de ROMs ("psx/Juego.chd").
type FileEntry struct {
	Path string `json:"path"`
	Size int64  `json:"size"`
}

// Part es un componente de un juego de Switch: el juego base, una actualización o un DLC.
type Part struct {
	Kind    string      `json:"kind"` // base, update, dlc
	Name    string      `json:"name"`
	TitleID string      `json:"titleId,omitempty"`
	Version int64       `json:"version,omitempty"`
	Size    int64       `json:"size"`
	Files   []FileEntry `json:"files"`
}

// Game es un juego de la biblioteca: uno o varios archivos que se descargan y borran juntos.
type Game struct {
	ID      string      `json:"id"`
	System  string      `json:"system"`
	Title   string      `json:"title"`
	Name    string      `json:"name"` // nombre del archivo principal sin extensión (para buscar carátulas locales)
	Tags    []string    `json:"tags,omitempty"`
	Kind    string      `json:"kind"` // file, multi, folder, switch
	Primary string      `json:"primary"`
	Size    int64       `json:"size"`
	Files   []FileEntry `json:"files"`
	Parts   []Part      `json:"parts,omitempty"`
	Serial  string      `json:"serial,omitempty"` // TitleID de Switch o de Vita, si el nombre lo trae
}

var (
	reBracket   = regexp.MustCompile(`\[[^\]]*\]`)
	reParen     = regexp.MustCompile(`\(([^)]*)\)`)
	reSpaces    = regexp.MustCompile(`\s{2,}`)
	reSwitchTID = regexp.MustCompile(`(?i)\[(0100[0-9a-f]{12})\]`)
	reSwitchVer = regexp.MustCompile(`(?i)\[v(\d+)\]`)
	reVitaID    = regexp.MustCompile(`\b(PCS[A-H]\d{5})\b`)
)

// Descriptores de juegos de varios archivos: su contenido dice qué otros archivos usan.
var descriptorExt = map[string]bool{".m3u": true, ".cue": true, ".gdi": true, ".ccd": true, ".mds": true}

// Marcas de carpetas que son un juego entero (PS3, Wii U, Vita instalada, juegos de PC).
var gameDirMarkers = map[string]bool{"ps3_game": true, "usrdir": true, "code": true, "content": true, "meta": true, "sce_sys": true, "eboot.bin": true}

func stem(name string) string {
	base := path.Base(name)
	if e := extOf(base); e != "" {
		return base[:len(base)-len(e)]
	}
	return base
}

// cleanTitle quita del nombre las etiquetas entre paréntesis y corchetes; devuelve el título y las etiquetas.
func cleanTitle(name string) (string, []string) {
	var tags []string
	for _, m := range reParen.FindAllStringSubmatch(name, -1) {
		t := strings.TrimSpace(m[1])
		if t != "" {
			tags = append(tags, t)
		}
	}
	t := reParen.ReplaceAllString(name, "")
	t = reBracket.ReplaceAllString(t, "")
	t = strings.ReplaceAll(t, "_", " ")
	t = reSpaces.ReplaceAllString(strings.TrimSpace(t), " ")
	t = strings.TrimRight(t, " -")
	if t == "" {
		t = name
	}
	return t, tags
}

func gameID(system, key string) string {
	h := sha1.Sum([]byte(strings.ToLower(system + ":" + key)))
	return hex.EncodeToString(h[:6])
}

// splitRefs lee las referencias de un descriptor (.cue, .m3u, .gdi). Devuelve rutas relativas a su carpeta.
func splitRefs(ext string, content []byte) []string {
	var refs []string
	for _, line := range strings.Split(strings.ReplaceAll(string(content), "\r", ""), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		switch ext {
		case ".m3u":
			refs = append(refs, line)
		case ".cue":
			if strings.HasPrefix(strings.ToUpper(line), "FILE ") {
				rest := strings.TrimSpace(line[5:])
				if strings.HasPrefix(rest, "\"") {
					if j := strings.Index(rest[1:], "\""); j >= 0 {
						refs = append(refs, rest[1:1+j])
					}
				} else if f := strings.Fields(rest); len(f) > 0 {
					refs = append(refs, f[0])
				}
			}
		case ".gdi":
			// "1 0 4 2352 track01.bin 0" o con el nombre entre comillas.
			if strings.Contains(line, "\"") {
				if a := strings.Index(line, "\""); a >= 0 {
					if b := strings.Index(line[a+1:], "\""); b >= 0 {
						refs = append(refs, line[a+1:a+1+b])
					}
				}
			} else if f := strings.Fields(line); len(f) >= 5 {
				refs = append(refs, f[4])
			}
		}
	}
	return refs
}

type grouper struct {
	system   string
	byKey    map[string]*FileEntry // ruta en minúsculas
	taken    map[string]bool
	readText func(p string) []byte
}

func (g *grouper) lookup(p string) *FileEntry {
	return g.byKey[strings.ToLower(path.Clean(p))]
}

// attached devuelve los archivos que usa un descriptor (sin él): por su contenido si se puede leer, si no por
// nombre (los .bin cuyo nombre empieza como el .cue, los discos cuyo nombre empieza como el .m3u).
func (g *grouper) attached(desc *FileEntry, all []*FileEntry) []*FileEntry {
	ext := extOf(desc.Path)
	dir := path.Dir(desc.Path)
	var out []*FileEntry
	seen := map[string]bool{strings.ToLower(desc.Path): true}
	add := func(f *FileEntry) {
		if f == nil {
			return
		}
		k := strings.ToLower(f.Path)
		if !seen[k] {
			seen[k] = true
			out = append(out, f)
		}
	}
	if ext == ".ccd" || ext == ".mds" {
		s := stem(desc.Path)
		for _, e := range []string{".img", ".sub", ".mdf"} {
			if f := g.lookup(path.Join(dir, s+e)); f != nil {
				add(f)
			}
		}
		return out
	}
	var content []byte
	if g.readText != nil {
		content = g.readText(desc.Path)
	}
	if content != nil {
		for _, r := range splitRefs(ext, content) {
			r = strings.ReplaceAll(r, "\\", "/")
			f := g.lookup(path.Join(dir, r))
			if f == nil {
				continue
			}
			add(f)
			if de := extOf(f.Path); descriptorExt[de] && de != ".m3u" {
				for _, sub := range g.attached(f, all) {
					add(sub)
				}
			}
		}
		if len(out) > 0 {
			return out
		}
	}
	// Sin contenido (tablet) o descriptor ilegible: por nombre.
	s := strings.ToLower(stem(desc.Path))
	for _, f := range all {
		if f == desc {
			continue
		}
		fe := extOf(f.Path)
		fs := strings.ToLower(stem(f.Path))
		fd := path.Dir(f.Path)
		switch ext {
		case ".cue", ".gdi":
			if fd == dir && strings.HasPrefix(fs, s) && (fe == ".bin" || fe == ".img" || fe == ".raw" || fe == ".wav" || fe == ".iso" || fe == ".sub") {
				add(f)
			}
		case ".m3u":
			inDir := fd == dir || strings.HasPrefix(fd, dir+"/")
			if inDir && fe != ".m3u" && strings.HasPrefix(fs, s) {
				add(f)
			}
		}
	}
	return out
}

// groupSystem arma los juegos de una plataforma. files tiene rutas relativas a la carpeta de ROMs, todas dentro
// de la carpeta de la plataforma. readText lee un descriptor (nil en la tablet: se agrupa por nombre).
func groupSystem(system string, files []FileEntry, readText func(p string) []byte) []*Game {
	g := &grouper{system: system, byKey: map[string]*FileEntry{}, taken: map[string]bool{}, readText: readText}
	all := make([]*FileEntry, 0, len(files))
	for i := range files {
		f := &files[i]
		all = append(all, f)
		g.byKey[strings.ToLower(path.Clean(f.Path))] = f
	}
	sort.Slice(all, func(i, j int) bool { return strings.ToLower(all[i].Path) < strings.ToLower(all[j].Path) })

	var games []*Game
	prefix := system + "/"

	// 1) Carpetas que son un juego entero.
	dirFiles := map[string][]*FileEntry{}
	for _, f := range all {
		rel := strings.TrimPrefix(f.Path, prefix)
		if i := strings.IndexByte(rel, '/'); i > 0 {
			d := rel[:i]
			dirFiles[d] = append(dirFiles[d], f)
		}
	}
	dirNames := make([]string, 0, len(dirFiles))
	for d := range dirFiles {
		dirNames = append(dirNames, d)
	}
	sort.Strings(dirNames)
	for _, d := range dirNames {
		fs := dirFiles[d]
		if !isGameDir(system, d, fs, prefix) {
			continue
		}
		game := newGame(system, d, prefix+d, "folder", fs)
		for _, f := range fs {
			g.taken[strings.ToLower(f.Path)] = true
		}
		if game.Primary == "" {
			game.Primary = prefix + d
		}
		games = append(games, game)
	}

	free := func() []*FileEntry {
		var out []*FileEntry
		for _, f := range all {
			if !g.taken[strings.ToLower(f.Path)] {
				out = append(out, f)
			}
		}
		return out
	}

	// 2) Descriptores: primero .cue/.gdi/.ccd/.mds, después .m3u (que se llevan los .cue de cada disco).
	for _, pass := range [][]string{{".cue", ".gdi", ".ccd", ".mds"}, {".m3u"}} {
		loose := free()
		var descs []*FileEntry
		for _, f := range loose {
			e := extOf(f.Path)
			for _, p := range pass {
				if e == p {
					descs = append(descs, f)
				}
			}
		}
		for _, d := range descs {
			if g.taken[strings.ToLower(d.Path)] {
				continue
			}
			parts := []*FileEntry{d}
			for _, f := range g.attached(d, all) {
				parts = append(parts, f)
			}
			// Un .m3u se lleva también los juegos ya armados con sus .cue.
			if extOf(d.Path) == ".m3u" {
				var keep []*Game
				for _, gm := range games {
					absorbed := false
					for _, p := range parts {
						if strings.EqualFold(p.Path, gm.Primary) {
							absorbed = true
						}
					}
					if absorbed && gm.Kind == "multi" {
						for i := range gm.Files {
							if f := g.lookup(gm.Files[i].Path); f != nil {
								parts = append(parts, f)
							}
						}
						continue
					}
					keep = append(keep, gm)
				}
				games = keep
			}
			parts = dedupe(parts)
			for _, p := range parts {
				g.taken[strings.ToLower(p.Path)] = true
			}
			games = append(games, newGame(system, stem(d.Path), d.Path, "multi", parts))
		}
	}

	// 3) El resto: un archivo, un juego (en Switch, agrupados por TitleID).
	loose := free()
	if strings.EqualFold(system, "switch") {
		games = append(games, groupSwitch(system, loose)...)
	} else {
		for _, f := range loose {
			games = append(games, newGame(system, stem(f.Path), f.Path, "file", []*FileEntry{f}))
		}
	}
	sort.Slice(games, func(i, j int) bool { return strings.ToLower(games[i].Title) < strings.ToLower(games[j].Title) })
	return games
}

func dedupe(fs []*FileEntry) []*FileEntry {
	seen := map[string]bool{}
	var out []*FileEntry
	for _, f := range fs {
		k := strings.ToLower(f.Path)
		if !seen[k] {
			seen[k] = true
			out = append(out, f)
		}
	}
	return out
}

func isGameDir(system, d string, fs []*FileEntry, prefix string) bool {
	ld := strings.ToLower(d)
	if ld == ".hidden" || ld == "updates" || ld == "update" || ld == "dlc" || ld == "dlcs" {
		return false
	}
	// "Juego.ps3", "Juego.m3u", "Juego.wiiu": ES-DE las trata como un archivo.
	if e := extOf(d); e != "" && len(e) <= 6 {
		return true
	}
	for _, f := range fs {
		rel := strings.TrimPrefix(f.Path, prefix+d+"/")
		parts := strings.Split(rel, "/")
		for _, p := range parts[:len(parts)-1] {
			if gameDirMarkers[strings.ToLower(p)] {
				return true
			}
		}
		if len(parts) == 1 {
			name := parts[0]
			if descriptorExt[extOf(name)] || strings.EqualFold(stem(name), d) || gameDirMarkers[strings.ToLower(name)] {
				return true
			}
		}
	}
	return false
}

func newGame(system, name, primary, kind string, fs []*FileEntry) *Game {
	title, tags := cleanTitle(name)
	g := &Game{System: system, Title: title, Name: name, Tags: tags, Kind: kind, Primary: primary}
	for _, f := range fs {
		g.Files = append(g.Files, *f)
		g.Size += f.Size
	}
	sort.Slice(g.Files, func(i, j int) bool { return g.Files[i].Path < g.Files[j].Path })
	if m := reVitaID.FindStringSubmatch(name); m != nil {
		g.Serial = m[1]
	}
	g.ID = gameID(system, primary)
	return g
}

// switchKind clasifica un TitleID de Switch: base (…000), actualización (…800) o DLC (base + 0x1000 + n).
func switchKind(tid uint64) (kind string, base uint64) {
	low := tid & 0xFFF
	switch {
	case low == 0:
		return "base", tid
	case low == 0x800:
		return "update", tid &^ 0xFFF
	default:
		return "dlc", (tid &^ 0xFFF) - 0x1000
	}
}

func groupSwitch(system string, files []*FileEntry) []*Game {
	type bucket struct {
		parts []Part
	}
	buckets := map[uint64]*bucket{}
	var order []uint64
	var games []*Game
	for _, f := range files {
		name := stem(f.Path)
		m := reSwitchTID.FindStringSubmatch(name)
		if m == nil {
			games = append(games, newGame(system, name, f.Path, "file", []*FileEntry{f}))
			continue
		}
		tid, err := strconv.ParseUint(m[1], 16, 64)
		if err != nil {
			games = append(games, newGame(system, name, f.Path, "file", []*FileEntry{f}))
			continue
		}
		kind, base := switchKind(tid)
		p := Part{Kind: kind, Name: name, TitleID: strings.ToUpper(m[1]), Size: f.Size, Files: []FileEntry{*f}}
		if v := reSwitchVer.FindStringSubmatch(name); v != nil {
			p.Version, _ = strconv.ParseInt(v[1], 10, 64)
		}
		b := buckets[base]
		if b == nil {
			b = &bucket{}
			buckets[base] = b
			order = append(order, base)
		}
		b.parts = append(b.parts, p)
	}
	for _, base := range order {
		b := buckets[base]
		sort.SliceStable(b.parts, func(i, j int) bool {
			rank := map[string]int{"base": 0, "update": 1, "dlc": 2}
			if rank[b.parts[i].Kind] != rank[b.parts[j].Kind] {
				return rank[b.parts[i].Kind] < rank[b.parts[j].Kind]
			}
			if b.parts[i].Version != b.parts[j].Version {
				return b.parts[i].Version > b.parts[j].Version
			}
			return b.parts[i].Name < b.parts[j].Name
		})
		head := b.parts[0]
		tidHex := strings.ToUpper(strconv.FormatUint(base, 16))
		for len(tidHex) < 16 {
			tidHex = "0" + tidHex
		}
		title, tags := cleanTitle(head.Name)
		g := &Game{System: system, Title: title, Name: head.Name, Tags: tags, Kind: "switch", Primary: head.Files[0].Path, Serial: tidHex}
		for _, p := range b.parts {
			g.Parts = append(g.Parts, p)
			g.Files = append(g.Files, p.Files...)
			g.Size += p.Size
		}
		// El ID depende del TitleID base: agregar una actualización no cambia el juego.
		g.ID = gameID(system, "tid:"+tidHex)
		games = append(games, g)
	}
	return games
}
