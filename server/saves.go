package main

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Respaldos de partidas: <carpeta>/<dispositivo>/<emulador>/<fecha>.zip con un .json al lado.
// La tablet manda un zip por emulador; si el contenido no cambió desde el último (mismo hash), no lo manda.

type SaveBackup struct {
	ID       string    `json:"id"`
	Device   string    `json:"device"`
	Emulator string    `json:"emulator"`
	At       time.Time `json:"at"`
	Size     int64     `json:"size"`
	Hash     string    `json:"hash"`
	Files    int       `json:"files"`
	Auto     bool      `json:"auto"`
	Keep     bool      `json:"keep"`
}

type Saves struct {
	mu  sync.Mutex
	dir func() string
	max func() int
}

func (s *Saves) emuDir(device, emu string) string {
	return filepath.Join(s.dir(), safeName(device), safeName(emu))
}

func (s *Saves) Store(meta SaveBackup, body io.Reader) (SaveBackup, error) {
	if meta.Device == "" || meta.Emulator == "" {
		return meta, errors.New("faltan el dispositivo o el emulador")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	dir := s.emuDir(meta.Device, meta.Emulator)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return meta, err
	}
	meta.At = time.Now()
	meta.ID = meta.At.Format("2006-01-02_150405")
	zipPath := filepath.Join(dir, meta.ID+".zip")
	tmp := zipPath + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return meta, err
	}
	n, err := io.Copy(f, body)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return meta, err
	}
	if n == 0 {
		os.Remove(tmp)
		return meta, errors.New("el respaldo llegó vacío")
	}
	if err := os.Rename(tmp, zipPath); err != nil {
		return meta, err
	}
	meta.Size = n
	data, _ := json.MarshalIndent(meta, "", "  ")
	if err := os.WriteFile(filepath.Join(dir, meta.ID+".json"), data, 0o644); err != nil {
		return meta, err
	}
	s.prune(meta.Device, meta.Emulator)
	return meta, nil
}

func (s *Saves) list(device, emu string) []SaveBackup {
	dir := s.emuDir(device, emu)
	entries, _ := os.ReadDir(dir)
	var out []SaveBackup
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		var b SaveBackup
		if json.Unmarshal(data, &b) == nil {
			if _, err := os.Stat(filepath.Join(dir, b.ID+".zip")); err == nil {
				out = append(out, b)
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].At.After(out[j].At) })
	return out
}

// prune borra los respaldos más viejos sobre el máximo (los marcados para conservar no cuentan ni se borran).
func (s *Saves) prune(device, emu string) {
	max := s.max()
	n := 0
	for _, b := range s.list(device, emu) {
		if b.Keep {
			continue
		}
		n++
		if n > max {
			dir := s.emuDir(device, emu)
			os.Remove(filepath.Join(dir, b.ID+".zip"))
			os.Remove(filepath.Join(dir, b.ID+".json"))
		}
	}
}

// All lista todos los respaldos (de todos los dispositivos si device = "").
func (s *Saves) All(device string) []SaveBackup {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []SaveBackup
	devs, _ := os.ReadDir(s.dir())
	for _, d := range devs {
		if !d.IsDir() || (device != "" && d.Name() != safeName(device)) {
			continue
		}
		emus, _ := os.ReadDir(filepath.Join(s.dir(), d.Name()))
		for _, e := range emus {
			if e.IsDir() {
				for _, b := range s.list(d.Name(), e.Name()) {
					out = append(out, b)
				}
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].At.After(out[j].At) })
	return out
}

func (s *Saves) Latest(device, emu string) *SaveBackup {
	l := s.list(device, emu)
	if len(l) == 0 {
		return nil
	}
	return &l[0]
}

func (s *Saves) File(device, emu, id string) (string, error) {
	if safeName(id) != id {
		return "", errors.New("id inválido")
	}
	p := filepath.Join(s.emuDir(device, emu), id+".zip")
	if _, err := os.Stat(p); err != nil {
		return "", err
	}
	return p, nil
}

func (s *Saves) SetKeep(device, emu, id string, keep bool) error {
	if safeName(id) != id {
		return errors.New("id inválido")
	}
	p := filepath.Join(s.emuDir(device, emu), id+".json")
	data, err := os.ReadFile(p)
	if err != nil {
		return err
	}
	var b SaveBackup
	if err := json.Unmarshal(data, &b); err != nil {
		return err
	}
	b.Keep = keep
	data, _ = json.MarshalIndent(b, "", "  ")
	return os.WriteFile(p, data, 0o644)
}

func (s *Saves) Delete(device, emu, id string) error {
	if safeName(id) != id {
		return errors.New("id inválido")
	}
	dir := s.emuDir(device, emu)
	os.Remove(filepath.Join(dir, id+".json"))
	return os.Remove(filepath.Join(dir, id+".zip"))
}
