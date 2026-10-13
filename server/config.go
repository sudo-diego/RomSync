package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Config se guarda en romsync.json junto al ejecutable. Se edita desde la web (Ajustes) o a mano.
type Config struct {
	// RomsDir es la carpeta con una subcarpeta por plataforma (estructura de ES-DE / Cocoon): ROMs/psx, ROMs/ps2…
	RomsDir string `json:"romsDir"`
	// BiosDir es la carpeta de BIOS. Vacía = "bios" dentro de RomsDir.
	BiosDir string `json:"biosDir"`
	// MediaDir (opcional) es downloaded_media de ES-DE: si tiene carátulas, se usan antes que SteamGridDB.
	MediaDir string `json:"mediaDir"`
	// BackupDir guarda los respaldos de partidas. Vacía = carpeta "respaldos" junto al ejecutable.
	BackupDir string `json:"backupDir"`
	Port      int    `json:"port"`
	// Code es el código que la tablet pide una vez para conectarse (lo muestra la web).
	Code           string `json:"code"`
	SteamGridDBKey string `json:"steamGridDbKey"`
	// KeepBackups es cuántos respaldos se guardan por emulador y dispositivo (los más viejos se borran).
	KeepBackups int    `json:"keepBackups"`
	Name        string `json:"name"`
}

type ConfigStore struct {
	mu   sync.RWMutex
	path string
	c    Config
}

func randomCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return "123456"
	}
	return fmt.Sprintf("%06d", n.Int64())
}

func loadConfig(path string) (*ConfigStore, error) {
	s := &ConfigStore{path: path}
	data, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	if len(data) > 0 {
		if err := json.Unmarshal(data, &s.c); err != nil {
			return nil, fmt.Errorf("%s no es JSON válido: %w", path, err)
		}
	}
	changed := false
	if s.c.Port == 0 {
		s.c.Port = 8642
		changed = true
	}
	if s.c.Code == "" {
		s.c.Code = randomCode()
		changed = true
	}
	if s.c.KeepBackups == 0 {
		s.c.KeepBackups = 30
		changed = true
	}
	if s.c.Name == "" {
		h, _ := os.Hostname()
		if h == "" {
			h = "PC"
		}
		s.c.Name = h
		changed = true
	}
	if changed || len(data) == 0 {
		if err := s.save(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *ConfigStore) Get() Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.c
}

func (s *ConfigStore) Set(c Config) error {
	s.mu.Lock()
	c.RomsDir = strings.TrimSpace(c.RomsDir)
	c.BiosDir = strings.TrimSpace(c.BiosDir)
	c.MediaDir = strings.TrimSpace(c.MediaDir)
	c.BackupDir = strings.TrimSpace(c.BackupDir)
	if c.Port == 0 {
		c.Port = s.c.Port
	}
	if c.Code == "" {
		c.Code = s.c.Code
	}
	if c.KeepBackups <= 0 {
		c.KeepBackups = 30
	}
	s.c = c
	s.mu.Unlock()
	return s.save()
}

func (s *ConfigStore) save() error {
	s.mu.RLock()
	data, _ := json.MarshalIndent(s.c, "", "  ")
	s.mu.RUnlock()
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (c Config) biosPath() string {
	if c.BiosDir != "" {
		return c.BiosDir
	}
	if c.RomsDir == "" {
		return ""
	}
	// Respeta "BIOS" o "bios" si ya existe.
	for _, n := range []string{"bios", "BIOS", "Bios"} {
		p := filepath.Join(c.RomsDir, n)
		if st, err := os.Stat(p); err == nil && st.IsDir() {
			return p
		}
	}
	return filepath.Join(c.RomsDir, "bios")
}

func (c Config) backupPath(baseDir string) string {
	if c.BackupDir != "" {
		return c.BackupDir
	}
	return filepath.Join(baseDir, "respaldos")
}
