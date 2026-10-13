package main

import (
	"encoding/json"
	"io"
	"io/fs"
	"log"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type SystemSummary struct {
	System
	Count int   `json:"count"`
	Size  int64 `json:"size"`
}

// Library es lo que hay en el disco del PC. Se vuelve a escanear cada 15 minutos o a pedido.
type Library struct {
	mu        sync.RWMutex
	games     []*Game
	byID      map[string]*Game
	systems   []SystemSummary
	bios      []FileEntry
	scannedAt time.Time
	scanning  bool
	err       string
	version   int64
}

type LibrarySnapshot struct {
	Version   int64           `json:"version"`
	ScannedAt time.Time       `json:"scannedAt"`
	Scanning  bool            `json:"scanning"`
	Error     string          `json:"error,omitempty"`
	Systems   []SystemSummary `json:"systems"`
	Games     []*Game         `json:"games"`
	Bios      []FileEntry     `json:"bios"`
}

func (l *Library) Snapshot() LibrarySnapshot {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return LibrarySnapshot{Version: l.version, ScannedAt: l.scannedAt, Scanning: l.scanning, Error: l.err,
		Systems: l.systems, Games: l.games, Bios: l.bios}
}

func (l *Library) Game(id string) *Game {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.byID[id]
}

// Scan recorre la carpeta de ROMs. Si ya hay un escaneo en curso, no hace nada.
func (l *Library) Scan(c Config) {
	l.mu.Lock()
	if l.scanning {
		l.mu.Unlock()
		return
	}
	l.scanning = true
	l.mu.Unlock()

	start := time.Now()
	games, systems, bios, err := scanLibrary(c)
	l.mu.Lock()
	defer l.mu.Unlock()
	l.scanning = false
	if err != nil {
		l.err = err.Error()
		log.Printf("escaneo: %v", err)
		return
	}
	l.err = ""
	l.games = games
	l.systems = systems
	l.bios = bios
	l.byID = make(map[string]*Game, len(games))
	for _, g := range games {
		l.byID[g.ID] = g
	}
	l.scannedAt = time.Now()
	l.version = l.scannedAt.UnixMilli()
	log.Printf("biblioteca: %d juegos en %d plataformas (%s)", len(games), len(systems), time.Since(start).Round(time.Millisecond))
}

func scanLibrary(c Config) ([]*Game, []SystemSummary, []FileEntry, error) {
	if c.RomsDir == "" {
		return nil, nil, nil, errString("Falta elegir la carpeta de ROMs (Ajustes)")
	}
	entries, err := os.ReadDir(c.RomsDir)
	if err != nil {
		return nil, nil, nil, err
	}
	biosAbs, _ := filepath.Abs(c.biosPath())
	var all []*Game
	var systems []SystemSummary
	for _, e := range entries {
		if !e.IsDir() || strings.HasPrefix(e.Name(), ".") || ignoredDirs[strings.ToLower(e.Name())] {
			continue
		}
		sysDir := filepath.Join(c.RomsDir, e.Name())
		if abs, _ := filepath.Abs(sysDir); strings.EqualFold(abs, biosAbs) {
			continue
		}
		files := walkFiles(c.RomsDir, sysDir)
		if len(files) == 0 {
			continue
		}
		readText := func(p string) []byte {
			f, err := os.Open(filepath.Join(c.RomsDir, filepath.FromSlash(p)))
			if err != nil {
				return nil
			}
			defer f.Close()
			data, _ := io.ReadAll(io.LimitReader(f, 1<<20))
			return data
		}
		games := groupSystem(e.Name(), files, readText)
		sum := SystemSummary{System: systemInfo(e.Name()), Count: len(games)}
		for _, g := range games {
			sum.Size += g.Size
		}
		systems = append(systems, sum)
		all = append(all, games...)
	}
	sort.Slice(systems, func(i, j int) bool { return strings.ToLower(systems[i].Name) < strings.ToLower(systems[j].Name) })
	var bios []FileEntry
	if st, err := os.Stat(biosAbs); err == nil && st.IsDir() {
		bios = walkFiles(biosAbs, biosAbs)
	}
	return all, systems, bios, nil
}

type errString string

func (e errString) Error() string { return string(e) }

// walkFiles lista los archivos bajo dir con rutas relativas a root (con "/"), sin medios ni partidas.
func walkFiles(root, dir string) []FileEntry {
	var out []FileEntry
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if p != dir && ignoredDirs[strings.ToLower(d.Name())] {
				return filepath.SkipDir
			}
			return nil
		}
		if isIgnoredFile(d.Name()) {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return nil
		}
		out = append(out, FileEntry{Path: filepath.ToSlash(rel), Size: info.Size()})
		return nil
	})
	return out
}

// ---- Lo que hay en la tablet ----

// DeviceReport lo manda la app de la tablet: sus ROMs y BIOS (rutas relativas a sus carpetas) y el espacio.
type DeviceReport struct {
	Device string      `json:"device"`
	Model  string      `json:"model"`
	Files  []FileEntry `json:"files"`
	Bios   []FileEntry `json:"bios"`
	Free   int64       `json:"free"`
	Total  int64       `json:"total"`
	At     time.Time   `json:"at"`
	// Emulators: lo que la app sabe de cada emulador (instalado, tamaño de sus datos, último respaldo).
	Emulators json.RawMessage `json:"emulators,omitempty"`
}

type GameState struct {
	ID        string   `json:"id"`
	State     string   `json:"state"` // installed, partial, missing
	LocalSize int64    `json:"localSize"`
	Missing   []string `json:"missing,omitempty"`
	// Differs: hay un archivo con el mismo nombre pero otro tamaño (otra versión o copia incompleta).
	Differs bool `json:"differs,omitempty"`
}

type CompareResult struct {
	Version     int64       `json:"version"`
	States      []GameState `json:"states"`
	TabletOnly  []*Game     `json:"tabletOnly"`
	BiosMissing []FileEntry `json:"biosMissing"`
	BiosPresent int         `json:"biosPresent"`
}

func compare(snap LibrarySnapshot, r DeviceReport) CompareResult {
	local := make(map[string]FileEntry, len(r.Files))
	for _, f := range r.Files {
		local[strings.ToLower(f.Path)] = f
	}
	used := map[string]bool{}
	res := CompareResult{Version: snap.Version, States: []GameState{}, TabletOnly: []*Game{}, BiosMissing: []FileEntry{}}
	for _, g := range snap.Games {
		st := GameState{ID: g.ID}
		present := 0
		for _, f := range g.Files {
			k := strings.ToLower(f.Path)
			if lf, ok := local[k]; ok {
				used[k] = true
				present++
				st.LocalSize += lf.Size
				if lf.Size != f.Size {
					st.Differs = true
				}
			} else {
				st.Missing = append(st.Missing, f.Path)
			}
		}
		switch {
		case present == 0:
			st.State = "missing"
			st.Missing = nil // todo falta: no hace falta la lista
		case present == len(g.Files):
			st.State = "installed"
		default:
			st.State = "partial"
		}
		if st.State != "missing" {
			res.States = append(res.States, st)
		}
	}
	// Lo que solo está en la tablet, agrupado por nombre (sin leer descriptores).
	bySystem := map[string][]FileEntry{}
	for _, f := range r.Files {
		if used[strings.ToLower(f.Path)] {
			continue
		}
		i := strings.IndexByte(f.Path, '/')
		if i <= 0 {
			continue
		}
		sys := f.Path[:i]
		bySystem[sys] = append(bySystem[sys], f)
	}
	for sys, files := range bySystem {
		for _, g := range groupSystem(sys, files, nil) {
			res.TabletOnly = append(res.TabletOnly, g)
		}
	}
	sort.Slice(res.TabletOnly, func(i, j int) bool { return res.TabletOnly[i].Title < res.TabletOnly[j].Title })

	lb := map[string]bool{}
	for _, b := range r.Bios {
		lb[strings.ToLower(b.Path)] = true
	}
	for _, b := range snap.Bios {
		if lb[strings.ToLower(b.Path)] {
			res.BiosPresent++
		} else {
			res.BiosMissing = append(res.BiosMissing, b)
		}
	}
	return res
}

// ---- Persistencia simple en la carpeta de datos ----

type Devices struct {
	mu  sync.Mutex
	dir string
}

func safeName(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := strings.Trim(b.String(), ".")
	if out == "" {
		return "tablet"
	}
	return out
}

func (d *Devices) Save(r DeviceReport) {
	d.mu.Lock()
	defer d.mu.Unlock()
	_ = os.MkdirAll(d.dir, 0o755)
	data, _ := json.Marshal(r)
	_ = os.WriteFile(filepath.Join(d.dir, safeName(r.Device)+".json"), data, 0o644)
}

func (d *Devices) All() []DeviceReport {
	d.mu.Lock()
	defer d.mu.Unlock()
	entries, _ := os.ReadDir(d.dir)
	var out []DeviceReport
	for _, e := range entries {
		if path.Ext(e.Name()) != ".json" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(d.dir, e.Name()))
		if err != nil {
			continue
		}
		var r DeviceReport
		if json.Unmarshal(data, &r) == nil {
			out = append(out, r)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].At.After(out[j].At) })
	return out
}
