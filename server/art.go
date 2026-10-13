package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Carátulas: primero las de ES-DE (downloaded_media), si no SteamGridDB. Todo queda en caché en disco, así la
// tablet no necesita internet ni la clave de la API.

const sgdbBase = "https://www.steamgriddb.com/api/v2"

var artKinds = map[string]string{"grid": "grids", "hero": "heroes", "logo": "logos", "icon": "icons"}

type artEntry struct {
	SgdbID  int               `json:"sgdbId"`
	Name    string            `json:"name"`
	Files   map[string]string `json:"files"`   // tipo → archivo en caché ("" = no hay)
	Checked map[string]int64  `json:"checked"` // tipo → cuándo se buscó (unix)
	Manual  bool              `json:"manual"`
}

type Art struct {
	mu      sync.Mutex
	dir     string
	index   map[string]*artEntry // id del juego → entrada
	cfg     *ConfigStore
	client  *http.Client
	sem     chan struct{}
	pending map[string]*sync.Mutex
}

func newArt(dir string, cfg *ConfigStore) *Art {
	a := &Art{dir: dir, cfg: cfg, index: map[string]*artEntry{}, client: &http.Client{Timeout: 25 * time.Second},
		sem: make(chan struct{}, 3), pending: map[string]*sync.Mutex{}}
	_ = os.MkdirAll(dir, 0o755)
	if data, err := os.ReadFile(filepath.Join(dir, "index.json")); err == nil {
		_ = json.Unmarshal(data, &a.index)
	}
	return a
}

func (a *Art) saveIndex() {
	data, _ := json.Marshal(a.index)
	_ = os.WriteFile(filepath.Join(a.dir, "index.json"), data, 0o644)
}

// lockFor evita buscar dos veces lo mismo cuando la tablet pide muchas carátulas a la vez.
func (a *Art) lockFor(key string) *sync.Mutex {
	a.mu.Lock()
	defer a.mu.Unlock()
	m := a.pending[key]
	if m == nil {
		m = &sync.Mutex{}
		a.pending[key] = m
	}
	return m
}

// localMedia busca la carátula de ES-DE: downloaded_media/<plataforma>/<carpeta>/<nombre>.png|jpg.
func localMedia(c Config, g *Game, kind string) string {
	if c.MediaDir == "" {
		return ""
	}
	folders := map[string][]string{
		"grid": {"covers", "box2dfront", "boxart"},
		"hero": {"fanart", "screenshots", "titlescreens"},
		"logo": {"marquees", "wheel"},
		"icon": {"miximages"},
	}[kind]
	names := []string{g.Name, stem(g.Primary)}
	for _, f := range folders {
		for _, n := range names {
			for _, ext := range []string{".png", ".jpg", ".jpeg", ".webp"} {
				p := filepath.Join(c.MediaDir, g.System, f, n+ext)
				if _, err := os.Stat(p); err == nil {
					return p
				}
			}
		}
	}
	return ""
}

// Path devuelve el archivo de la imagen (descargándola si hace falta) o "" si no hay.
func (a *Art) Path(g *Game, kind string) (string, error) {
	c := a.cfg.Get()
	if p := localMedia(c, g, kind); p != "" {
		return p, nil
	}
	if c.SteamGridDBKey == "" {
		return "", nil
	}
	lk := a.lockFor(g.ID + "/" + kind)
	lk.Lock()
	defer lk.Unlock()

	a.mu.Lock()
	e := a.index[g.ID]
	if e == nil {
		e = &artEntry{Files: map[string]string{}, Checked: map[string]int64{}}
		a.index[g.ID] = e
	}
	if f, ok := e.Files[kind]; ok && f != "" {
		a.mu.Unlock()
		p := filepath.Join(a.dir, f)
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
		a.mu.Lock()
	}
	// Sin resultado: se vuelve a intentar a los 7 días.
	if t, ok := e.Checked[kind]; ok && e.Files[kind] == "" && time.Since(time.Unix(t, 0)) < 7*24*time.Hour {
		a.mu.Unlock()
		return "", nil
	}
	sgdbID := e.SgdbID
	a.mu.Unlock()

	a.sem <- struct{}{}
	defer func() { <-a.sem }()

	if sgdbID < 0 {
		sgdbID = 0 // pasó una semana sin resultado: se busca de nuevo
	}
	if sgdbID == 0 {
		id, name, err := a.findGame(c.SteamGridDBKey, g)
		if err != nil {
			return "", err
		}
		a.mu.Lock()
		e.SgdbID, e.Name = id, name
		a.mu.Unlock()
		sgdbID = id
	}
	file := ""
	if sgdbID > 0 {
		u, err := a.firstImage(c.SteamGridDBKey, kind, sgdbID)
		if err != nil {
			return "", err
		}
		if u != "" {
			file, err = a.download(u, fmt.Sprintf("%s_%s%s", g.ID, kind, imageExt(u)))
			if err != nil {
				return "", err
			}
		}
	}
	a.mu.Lock()
	e.Files[kind] = file
	e.Checked[kind] = time.Now().Unix()
	a.saveIndex()
	a.mu.Unlock()
	if file == "" {
		return "", nil
	}
	return filepath.Join(a.dir, file), nil
}

func imageExt(u string) string {
	if pu, err := url.Parse(u); err == nil {
		if e := strings.ToLower(filepath.Ext(pu.Path)); e == ".png" || e == ".jpg" || e == ".jpeg" || e == ".webp" || e == ".ico" {
			return e
		}
	}
	return ".png"
}

type sgdbSearch struct {
	Success bool `json:"success"`
	Data    []struct {
		ID       int    `json:"id"`
		Name     string `json:"name"`
		Verified bool   `json:"verified"`
	} `json:"data"`
}

type sgdbImages struct {
	Success bool `json:"success"`
	Data    []struct {
		URL   string `json:"url"`
		Thumb string `json:"thumb"`
		Style string `json:"style"`
	} `json:"data"`
}

func (a *Art) get(key, endpoint string, out any) error {
	req, _ := http.NewRequest("GET", sgdbBase+endpoint, nil)
	req.Header.Set("Authorization", "Bearer "+key)
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 404 {
		return nil
	}
	if resp.StatusCode == 401 || resp.StatusCode == 403 {
		return errors.New("SteamGridDB rechazó la clave de la API")
	}
	if resp.StatusCode != 200 {
		return fmt.Errorf("SteamGridDB respondió %d", resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func normalize(s string) string {
	s = strings.ToLower(s)
	var b strings.Builder
	for _, r := range s {
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// Search consulta el autocompletado de SteamGridDB (para elegir la carátula a mano en la web).
func (a *Art) Search(q string) ([]map[string]any, error) {
	key := a.cfg.Get().SteamGridDBKey
	if key == "" {
		return nil, errors.New("Falta la clave de SteamGridDB (Ajustes)")
	}
	var res sgdbSearch
	if err := a.get(key, "/search/autocomplete/"+url.PathEscape(q), &res); err != nil {
		return nil, err
	}
	out := []map[string]any{}
	for _, d := range res.Data {
		out = append(out, map[string]any{"id": d.ID, "name": d.Name})
	}
	return out, nil
}

func (a *Art) findGame(key string, g *Game) (int, string, error) {
	title := g.Title
	// "Juego - Subtítulo" a veces está como "Juego: Subtítulo" en SteamGridDB: el autocompletado lo resuelve.
	var res sgdbSearch
	if err := a.get(key, "/search/autocomplete/"+url.PathEscape(title), &res); err != nil {
		return 0, "", err
	}
	if len(res.Data) == 0 {
		return -1, "", nil
	}
	want := normalize(title)
	for _, d := range res.Data {
		if normalize(d.Name) == want {
			return d.ID, d.Name, nil
		}
	}
	return res.Data[0].ID, res.Data[0].Name, nil
}

func (a *Art) firstImage(key, kind string, id int) (string, error) {
	ep := fmt.Sprintf("/%s/game/%d?types=static&nsfw=false&humor=false", artKinds[kind], id)
	if kind == "grid" {
		ep += "&dimensions=600x900,342x482,660x930"
	}
	var res sgdbImages
	if err := a.get(key, ep, &res); err != nil {
		return "", err
	}
	if len(res.Data) == 0 && kind == "grid" {
		// Sin carátulas verticales: cualquier grid sirve.
		if err := a.get(key, fmt.Sprintf("/grids/game/%d?types=static&nsfw=false&humor=false", id), &res); err != nil {
			return "", err
		}
	}
	if len(res.Data) == 0 {
		return "", nil
	}
	return res.Data[0].URL, nil
}

func (a *Art) download(u, name string) (string, error) {
	resp, err := a.client.Get(u)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("descarga de imagen: %d", resp.StatusCode)
	}
	tmp := filepath.Join(a.dir, name+".tmp")
	f, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(f, io.LimitReader(resp.Body, 20<<20)); err != nil {
		f.Close()
		os.Remove(tmp)
		return "", err
	}
	f.Close()
	if err := os.Rename(tmp, filepath.Join(a.dir, name)); err != nil {
		return "", err
	}
	return name, nil
}

// Choose fija a mano el juego de SteamGridDB para un juego y borra lo que había en caché.
func (a *Art) Choose(gameID string, sgdbID int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if old := a.index[gameID]; old != nil {
		for _, f := range old.Files {
			if f != "" {
				_ = os.Remove(filepath.Join(a.dir, f))
			}
		}
	}
	a.index[gameID] = &artEntry{SgdbID: sgdbID, Files: map[string]string{}, Checked: map[string]int64{}, Manual: true}
	a.saveIndex()
	log.Printf("carátula de %s fijada a SteamGridDB %d", gameID, sgdbID)
}

// Prefetch baja en segundo plano las carátulas que faltan (las de la tablet primero, si se piden).
func (a *Art) Prefetch(games []*Game) {
	for _, g := range games {
		if _, err := a.Path(g, "grid"); err != nil {
			log.Printf("carátulas: %v", err)
			if strings.Contains(err.Error(), "clave") {
				return
			}
		}
	}
}
