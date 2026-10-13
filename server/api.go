package main

import (
	"compress/gzip"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Server struct {
	cfg     *ConfigStore
	lib     *Library
	art     *Art
	saves   *Saves
	devices *Devices
	queue   *Queue
	dataDir string
	baseDir string
}

// ---- Cola "Enviar a la tablet": la web elige juegos y la app los descarga al conectarse ----

type QueueItem struct {
	GameID string    `json:"gameId"`
	Parts  []string  `json:"parts,omitempty"` // TitleIDs de Switch elegidos (vacío = todo)
	Added  time.Time `json:"added"`
}

type Queue struct {
	mu   sync.Mutex
	path string
}

func (q *Queue) load() []QueueItem {
	var items []QueueItem
	if data, err := os.ReadFile(q.path); err == nil {
		_ = json.Unmarshal(data, &items)
	}
	if items == nil {
		items = []QueueItem{}
	}
	return items
}

func (q *Queue) save(items []QueueItem) {
	data, _ := json.MarshalIndent(items, "", "  ")
	_ = os.WriteFile(q.path, data, 0o644)
}

func (q *Queue) List() []QueueItem {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.load()
}

func (q *Queue) Add(it QueueItem) {
	q.mu.Lock()
	defer q.mu.Unlock()
	items := q.load()
	for i := range items {
		if items[i].GameID == it.GameID {
			items[i].Parts = it.Parts
			q.save(items)
			return
		}
	}
	it.Added = time.Now()
	q.save(append(items, it))
}

func (q *Queue) Remove(id string) {
	q.mu.Lock()
	defer q.mu.Unlock()
	items := q.load()
	out := items[:0]
	for _, it := range items {
		if it.GameID != id {
			out = append(out, it)
		}
	}
	q.save(out)
}

// ---- Utilidades HTTP ----

func writeJSON(w http.ResponseWriter, r *http.Request, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		w.Header().Set("Content-Encoding", "gzip")
		gz := gzip.NewWriter(w)
		defer gz.Close()
		_ = json.NewEncoder(gz).Encode(v)
		return
	}
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func isLocal(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// auth: desde el mismo PC no pide nada; desde otro equipo pide el código (cabecera, cookie o ?k=).
func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !isLocal(r) {
			code := s.cfg.Get().Code
			got := r.Header.Get("X-RomSync-Code")
			if got == "" {
				got = r.URL.Query().Get("k")
			}
			if got == "" {
				if c, err := r.Cookie("romsync_code"); err == nil {
					got = c.Value
				}
			}
			if got != code {
				writeErr(w, http.StatusUnauthorized, "Código incorrecto")
				return
			}
		}
		next(w, r)
	}
}

func localOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !isLocal(r) {
			writeErr(w, http.StatusForbidden, "Solo desde el PC")
			return
		}
		next(w, r)
	}
}

// safeJoin resuelve una ruta relativa dentro de root sin dejar salir de ella.
func safeJoin(root, rel string) (string, error) {
	if root == "" {
		return "", errors.New("carpeta sin configurar")
	}
	rel = strings.ReplaceAll(rel, "\\", "/")
	clean := path.Clean("/" + rel)
	if clean == "/" || strings.Contains(clean, "/../") {
		return "", errors.New("ruta inválida")
	}
	p := filepath.Join(root, filepath.FromSlash(strings.TrimPrefix(clean, "/")))
	absRoot, _ := filepath.Abs(root)
	absP, _ := filepath.Abs(p)
	if !strings.HasPrefix(strings.ToLower(absP), strings.ToLower(absRoot)+string(filepath.Separator)) {
		return "", errors.New("ruta fuera de la biblioteca")
	}
	return p, nil
}

// ---- Rutas ----

func (s *Server) routes(web http.Handler) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/info", s.handleInfo)
	mux.HandleFunc("/api/login", s.handleLogin)
	mux.HandleFunc("/api/library", s.auth(s.handleLibrary))
	mux.HandleFunc("/api/rescan", s.auth(s.handleRescan))
	mux.HandleFunc("/api/compare", s.auth(s.handleCompare))
	mux.HandleFunc("/api/devices", s.auth(s.handleDevices))
	mux.HandleFunc("/api/file", s.auth(s.handleFile))
	mux.HandleFunc("/api/bios-file", s.auth(s.handleBiosFile))
	mux.HandleFunc("/api/art/", s.auth(s.handleArt))
	mux.HandleFunc("/api/art-search", s.auth(s.handleArtSearch))
	mux.HandleFunc("/api/art-choose", s.auth(s.handleArtChoose))
	mux.HandleFunc("/api/queue", s.auth(s.handleQueue))
	mux.HandleFunc("/api/saves", s.auth(s.handleSaves))
	mux.HandleFunc("/api/saves/upload", s.auth(s.handleSaveUpload))
	mux.HandleFunc("/api/saves/latest", s.auth(s.handleSaveLatest))
	mux.HandleFunc("/api/saves/download", s.auth(s.handleSaveDownload))
	mux.HandleFunc("/api/saves/keep", s.auth(s.handleSaveKeep))
	mux.HandleFunc("/api/saves/delete", s.auth(s.handleSaveDelete))
	mux.HandleFunc("/api/config", localOnly(s.handleConfig))
	mux.HandleFunc("/api/browse", localOnly(s.handleBrowse))
	mux.Handle("/", web)
	return mux
}

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Get()
	snap := s.lib.Snapshot()
	info := map[string]any{
		"app": "RomSync", "version": appVersion, "name": c.Name, "local": isLocal(r),
		"configured": c.RomsDir != "", "games": len(snap.Games), "scanning": snap.Scanning,
		"hasArtKey": c.SteamGridDBKey != "", "systems": knownSystems,
	}
	if isLocal(r) {
		info["code"] = c.Code
		info["addresses"] = lanAddresses()
		info["port"] = c.Port
	}
	writeJSON(w, r, info)
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Code string `json:"code"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	if body.Code != s.cfg.Get().Code {
		writeErr(w, http.StatusUnauthorized, "Código incorrecto")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: "romsync_code", Value: body.Code, Path: "/", MaxAge: 365 * 24 * 3600, HttpOnly: true})
	writeJSON(w, r, map[string]bool{"ok": true})
}

func (s *Server) handleLibrary(w http.ResponseWriter, r *http.Request) {
	snap := s.lib.Snapshot()
	if v := r.URL.Query().Get("since"); v != "" && v == strconv.FormatInt(snap.Version, 10) && !snap.Scanning {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	writeJSON(w, r, snap)
}

func (s *Server) handleRescan(w http.ResponseWriter, r *http.Request) {
	go s.lib.Scan(s.cfg.Get())
	writeJSON(w, r, map[string]bool{"ok": true})
}

func (s *Server) handleCompare(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST")
		return
	}
	var rep DeviceReport
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<20)).Decode(&rep); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if rep.Device == "" {
		rep.Device = "tablet"
	}
	rep.At = time.Now()
	s.devices.Save(rep)
	res := compare(s.lib.Snapshot(), rep)
	writeJSON(w, r, res)
}

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	snap := s.lib.Snapshot()
	type dev struct {
		DeviceReport
		Compare CompareResult `json:"compare"`
	}
	var out []dev
	for _, d := range s.devices.All() {
		out = append(out, dev{DeviceReport: d, Compare: compare(snap, d)})
	}
	if out == nil {
		out = []dev{}
	}
	writeJSON(w, r, out)
}

func serveFile(w http.ResponseWriter, r *http.Request, p string) {
	f, err := os.Open(p)
	if err != nil {
		writeErr(w, http.StatusNotFound, "No existe")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		writeErr(w, http.StatusNotFound, "No existe")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	// ServeContent atiende Range: la tablet retoma descargas cortadas.
	http.ServeContent(w, r, st.Name(), st.ModTime(), f)
}

func (s *Server) handleFile(w http.ResponseWriter, r *http.Request) {
	p, err := safeJoin(s.cfg.Get().RomsDir, r.URL.Query().Get("p"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	serveFile(w, r, p)
}

func (s *Server) handleBiosFile(w http.ResponseWriter, r *http.Request) {
	p, err := safeJoin(s.cfg.Get().biosPath(), r.URL.Query().Get("p"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	serveFile(w, r, p)
}

// /api/art/<id>/<grid|hero|logo|icon>
func (s *Server) handleArt(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/art/"), "/")
	if len(parts) != 2 || artKinds[parts[1]] == "" {
		writeErr(w, http.StatusBadRequest, "ruta inválida")
		return
	}
	g := s.lib.Game(parts[0])
	if g == nil {
		g = s.tabletGame(parts[0])
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "juego desconocido")
		return
	}
	p, err := s.art.Path(g, parts[1])
	if err != nil {
		log.Printf("carátula %s: %v", g.Title, err)
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	if p == "" {
		w.Header().Set("Cache-Control", "max-age=3600")
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Cache-Control", "max-age=604800")
	http.ServeFile(w, r, p)
}

// tabletGame busca un juego que solo está en la tablet (para mostrar su carátula también).
func (s *Server) tabletGame(id string) *Game {
	snap := s.lib.Snapshot()
	for _, d := range s.devices.All() {
		for _, g := range compare(snap, d).TabletOnly {
			if g.ID == id {
				return g
			}
		}
	}
	return nil
}

func (s *Server) handleArtSearch(w http.ResponseWriter, r *http.Request) {
	res, err := s.art.Search(r.URL.Query().Get("q"))
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, r, res)
}

func (s *Server) handleArtChoose(w http.ResponseWriter, r *http.Request) {
	var body struct {
		GameID string `json:"gameId"`
		SgdbID int    `json:"sgdbId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.GameID == "" || body.SgdbID <= 0 {
		writeErr(w, http.StatusBadRequest, "datos inválidos")
		return
	}
	s.art.Choose(body.GameID, body.SgdbID)
	writeJSON(w, r, map[string]bool{"ok": true})
}

func (s *Server) handleQueue(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, r, s.queue.List())
	case http.MethodPost:
		var it QueueItem
		if err := json.NewDecoder(r.Body).Decode(&it); err != nil || it.GameID == "" {
			writeErr(w, http.StatusBadRequest, "datos inválidos")
			return
		}
		s.queue.Add(it)
		writeJSON(w, r, s.queue.List())
	case http.MethodDelete:
		s.queue.Remove(r.URL.Query().Get("id"))
		writeJSON(w, r, s.queue.List())
	default:
		writeErr(w, http.StatusMethodNotAllowed, "método")
	}
}

func (s *Server) handleSaves(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, r, s.saves.All(r.URL.Query().Get("device")))
}

func (s *Server) handleSaveUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST")
		return
	}
	q := r.URL.Query()
	files, _ := strconv.Atoi(q.Get("files"))
	meta := SaveBackup{Device: q.Get("device"), Emulator: q.Get("emulator"), Hash: q.Get("hash"), Files: files, Auto: q.Get("auto") == "1"}
	b, err := s.saves.Store(meta, io.LimitReader(r.Body, 8<<30))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	log.Printf("respaldo de %s (%s): %d archivos, %d KB", b.Emulator, b.Device, b.Files, b.Size/1024)
	writeJSON(w, r, b)
}

func (s *Server) handleSaveLatest(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	b := s.saves.Latest(q.Get("device"), q.Get("emulator"))
	if b == nil {
		writeJSON(w, r, map[string]any{})
		return
	}
	writeJSON(w, r, b)
}

func (s *Server) handleSaveDownload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	p, err := s.saves.File(q.Get("device"), q.Get("emulator"), q.Get("id"))
	if err != nil {
		writeErr(w, http.StatusNotFound, "No existe")
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="`+safeName(q.Get("emulator"))+"_"+q.Get("id")+`.zip"`)
	http.ServeFile(w, r, p)
}

func (s *Server) handleSaveKeep(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if err := s.saves.SetKeep(q.Get("device"), q.Get("emulator"), q.Get("id"), q.Get("keep") == "1"); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, r, map[string]bool{"ok": true})
}

func (s *Server) handleSaveDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST")
		return
	}
	q := r.URL.Query()
	if err := s.saves.Delete(q.Get("device"), q.Get("emulator"), q.Get("id")); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, r, map[string]bool{"ok": true})
}

func (s *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		c := s.cfg.Get()
		writeJSON(w, r, map[string]any{"config": c, "biosResolved": c.biosPath(), "backupResolved": c.backupPath(s.baseDir)})
	case http.MethodPost:
		var c Config
		if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		if c.RomsDir != "" {
			if st, err := os.Stat(c.RomsDir); err != nil || !st.IsDir() {
				writeErr(w, http.StatusBadRequest, "La carpeta de ROMs no existe: "+c.RomsDir)
				return
			}
		}
		old := s.cfg.Get()
		if err := s.cfg.Set(c); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		go s.lib.Scan(s.cfg.Get())
		resp := map[string]any{"ok": true}
		if c.Port != 0 && c.Port != old.Port {
			resp["restart"] = true
		}
		writeJSON(w, r, resp)
	default:
		writeErr(w, http.StatusMethodNotAllowed, "método")
	}
}

// handleBrowse lista carpetas para elegir rutas desde la web (solo desde el mismo PC).
func (s *Server) handleBrowse(w http.ResponseWriter, r *http.Request) {
	dir := r.URL.Query().Get("dir")
	type entry struct {
		Name string `json:"name"`
		Path string `json:"path"`
	}
	out := struct {
		Dir     string  `json:"dir"`
		Parent  string  `json:"parent"`
		Entries []entry `json:"entries"`
	}{Dir: dir, Entries: []entry{}}
	if dir == "" {
		for _, root := range rootDirs() {
			out.Entries = append(out.Entries, entry{Name: root, Path: root})
		}
		writeJSON(w, r, out)
		return
	}
	items, err := os.ReadDir(dir)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	parent := filepath.Dir(filepath.Clean(dir))
	if parent != filepath.Clean(dir) {
		out.Parent = parent
	}
	for _, it := range items {
		if it.IsDir() && !strings.HasPrefix(it.Name(), "$") && !strings.HasPrefix(it.Name(), ".") {
			out.Entries = append(out.Entries, entry{Name: it.Name(), Path: filepath.Join(dir, it.Name())})
		}
	}
	writeJSON(w, r, out)
}

func rootDirs() []string {
	var out []string
	if filepath.Separator == '\\' {
		for c := 'C'; c <= 'Z'; c++ {
			d := string(c) + `:\`
			if _, err := os.Stat(d); err == nil {
				out = append(out, d)
			}
		}
		return out
	}
	home, _ := os.UserHomeDir()
	return []string{"/", home}
}

func lanAddresses() []string {
	var out []string
	ifaces, _ := net.Interfaces()
	for _, i := range ifaces {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && ipn.IP.IsPrivate() {
				out = append(out, ipn.IP.String())
			}
		}
	}
	return out
}
