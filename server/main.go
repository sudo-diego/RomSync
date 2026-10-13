package main

import (
	"embed"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const appVersion = "0.1.0"

//go:embed web
var webFS embed.FS

func main() {
	exe, _ := os.Executable()
	baseDir := filepath.Dir(exe)
	if strings.Contains(baseDir, "go-build") { // go run: usar la carpeta actual
		baseDir, _ = os.Getwd()
	}
	cfgPath := flag.String("config", filepath.Join(baseDir, "romsync.json"), "archivo de configuración")
	noBrowser := flag.Bool("no-browser", false, "no abrir el navegador al iniciar")
	flag.Parse()
	baseDir = filepath.Dir(*cfgPath)

	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		log.Fatalf("configuración: %v", err)
	}
	dataDir := filepath.Join(baseDir, "romsync-data")
	_ = os.MkdirAll(dataDir, 0o755)

	s := &Server{
		cfg:     cfg,
		lib:     &Library{},
		art:     newArt(filepath.Join(dataDir, "art"), cfg),
		devices: &Devices{dir: filepath.Join(dataDir, "devices")},
		queue:   &Queue{path: filepath.Join(dataDir, "queue.json")},
		dataDir: dataDir,
		baseDir: baseDir,
	}
	s.saves = &Saves{
		dir: func() string { return cfg.Get().backupPath(baseDir) },
		max: func() int { return cfg.Get().KeepBackups },
	}

	go func() {
		for {
			s.lib.Scan(cfg.Get())
			// Carátulas en segundo plano: así la web y la tablet las tienen listas.
			go s.art.Prefetch(s.lib.Snapshot().Games)
			time.Sleep(15 * time.Minute)
		}
	}()

	c := cfg.Get()
	go discovery(cfg)

	sub, _ := fs.Sub(webFS, "web")
	web := http.FileServer(http.FS(sub))
	addr := fmt.Sprintf(":%d", c.Port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("no se pudo abrir el puerto %d (¿RomSync ya está abierto?): %v", c.Port, err)
	}
	local := fmt.Sprintf("http://localhost:%d", c.Port)
	fmt.Println("RomSync", appVersion)
	fmt.Println("  Web:     ", local)
	for _, a := range lanAddresses() {
		fmt.Printf("  En la red: http://%s:%d\n", a, c.Port)
	}
	fmt.Println("  Código para la tablet:", c.Code)
	fmt.Println("  Cierra esta ventana para detener RomSync.")
	if !*noBrowser {
		openBrowser(local)
	}
	srv := &http.Server{Handler: logRequests(s.routes(web)), ReadHeaderTimeout: 15 * time.Second}
	log.Fatal(srv.Serve(ln))
}

func logRequests(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/file") || strings.HasPrefix(r.URL.Path, "/api/bios-file") {
			log.Printf("%s descarga %s", r.RemoteAddr, r.URL.Query().Get("p"))
		}
		h.ServeHTTP(w, r)
	})
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

// discovery responde a la tablet cuando busca el PC en la red (UDP 8643, mensaje "ROMSYNC_DISCOVER").
func discovery(cfg *ConfigStore) {
	pc, err := net.ListenPacket("udp4", ":8643")
	if err != nil {
		log.Printf("búsqueda en la red desactivada: %v", err)
		return
	}
	defer pc.Close()
	buf := make([]byte, 256)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			continue
		}
		if strings.TrimSpace(string(buf[:n])) != "ROMSYNC_DISCOVER" {
			continue
		}
		c := cfg.Get()
		reply, _ := json.Marshal(map[string]any{"app": "RomSync", "name": c.Name, "port": c.Port, "version": appVersion})
		_, _ = pc.WriteTo(reply, from)
	}
}
